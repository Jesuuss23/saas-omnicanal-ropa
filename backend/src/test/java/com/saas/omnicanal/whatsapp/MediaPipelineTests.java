package com.saas.omnicanal.whatsapp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.saas.omnicanal.assistant.model.AssistantModels.Answer;
import com.saas.omnicanal.assistant.repository.AssistantRepository;
import com.saas.omnicanal.assistant.service.AssistantResponder;
import com.saas.omnicanal.inbox.service.InboxService;
import com.saas.omnicanal.media.ModerationClient;
import com.saas.omnicanal.media.TranscriptionClient;
import com.saas.omnicanal.media.VisionClient;
import com.saas.omnicanal.whatsapp.model.CloudApiCredentials;
import com.saas.omnicanal.whatsapp.model.InboundMediaEvent;
import com.saas.omnicanal.whatsapp.model.MediaKind;
import com.saas.omnicanal.whatsapp.model.SendResult;
import com.saas.omnicanal.whatsapp.model.WhatsAppProvider;
import com.saas.omnicanal.whatsapp.repository.WhatsAppMediaRepository;
import com.saas.omnicanal.whatsapp.service.CloudApiClient;
import com.saas.omnicanal.whatsapp.service.CloudApiService;
import com.saas.omnicanal.whatsapp.service.EvolutionApiClient;
import com.saas.omnicanal.whatsapp.service.EvolutionWebhookService;
import com.saas.omnicanal.whatsapp.service.WhatsAppMediaService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

/**
 * Pipeline de archivos entrantes: Groq (voz), Sightengine (contenido explícito) y visión (atributos + similares), con
 * caché en la base. La IA conversacional solo recibe texto.
 */
@SpringBootTest
class MediaPipelineTests {
    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry props) {
        props.add("EVOLUTION_WEBHOOK_SECRET", () -> "secreto-de-prueba");
    }

    @Autowired JdbcTemplate db;
    @Autowired EvolutionWebhookService webhooks;
    @Autowired WhatsAppMediaService pipeline;
    @Autowired WhatsAppMediaRepository media;
    @Autowired AssistantRepository assistantRepo;
    @Autowired InboxService inbox;
    @Autowired ObjectMapper json;
    @MockitoBean EvolutionApiClient evolution;
    @MockitoBean CloudApiClient cloud;
    @MockitoBean CloudApiService cloudAccounts;
    @MockitoBean ModerationClient moderation;
    @MockitoBean TranscriptionClient transcription;
    @MockitoBean VisionClient vision;
    @MockitoBean AssistantResponder responder;

    UUID store;
    String instance;
    final String chat = "51955544433@s.whatsapp.net";
    final byte[] jpg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, 3, 4, 5, 6, 7, 8, 9};

    @BeforeEach
    void setUp() {
        store = UUID.randomUUID();
        instance = EvolutionApiClient.instanceName(store);
        db.update("INSERT INTO tenant(id,name,whatsapp_instance_id) VALUES (?,?,?)", store, "Tienda Fotos", instance);
        db.update("INSERT INTO tenant_owner(tenant_id,email,password_hash) VALUES (?,?, 'x')", store, store + "@fotos.test");
        db.update("INSERT INTO tenant_subscription(tenant_id,plan_code,status,trial_ends_at) VALUES (?, 'TRIAL', 'TRIAL', now() + interval '5 days')", store);
        assertTrue(assistantRepo.saveSettings(store, 0, true, "", 10));
        when(evolution.configured()).thenReturn(true);
        when(evolution.sendText(anyString(), anyString(), anyString())).thenReturn(SendResult.accepted("OUT-" + UUID.randomUUID()));
        when(responder.configured()).thenReturn(true);
        when(responder.answer(eq(store), any(), anyString(), anyList(), any(), anyString()))
                .thenReturn(new Answer("Claro, tenemos opciones.", false, null, null, List.of("buscarPrendas")));
        when(moderation.enabled()).thenReturn(true);
        when(transcription.enabled()).thenReturn(true);
        when(vision.enabled()).thenReturn(true);
    }

    @Test
    void voiceNoteIsTranscribedOnceAndReachesTheAssistantAsText() {
        byte[] audio = "OggS-nota".getBytes(StandardCharsets.UTF_8);
        when(evolution.media(anyString(), any())).thenReturn(new EvolutionApiClient.Downloaded(audio, "audio/ogg; codecs=opus", null));
        when(transcription.transcribe(any(), anyString())).thenReturn("Hola, ¿tienen polos negros en talla M?");
        upsert("A1", "{\"audioMessage\":{\"mimetype\":\"audio/ogg; codecs=opus\",\"ptt\":true}}");

        verify(responder, timeout(5000)).answer(eq(store), any(), anyString(), anyList(), any(),
                contains("Hola, ¿tienen polos negros en talla M?"));
        var message = inbox.conversation(store, chat).messages().get(0);
        assertEquals("[Nota de voz]", message.body());
        assertEquals("Hola, ¿tienen polos negros en talla M?", message.transcript());

        // Reprocesar el mismo mensaje o recibir el mismo audio reenviado no vuelve a llamar a Groq.
        UUID id = mediaId("A1");
        pipeline.process(event("A1", MediaKind.AUDIO), id, audio, "audio/ogg");
        upsert("A2", "{\"audioMessage\":{\"mimetype\":\"audio/ogg; codecs=opus\",\"ptt\":true}}");
        verify(responder, timeout(5000).atLeast(2)).answer(eq(store), any(), anyString(), anyList(), any(), anyString());
        verify(transcription, times(1)).transcribe(any(), anyString());
        assertEquals("Hola, ¿tienen polos negros en talla M?", media.analysis(store, mediaId("A2")).transcript());
    }

    @Test
    void explicitImageIsBlockedWarnedByEvolutionAndNeverAnalyzed() {
        when(evolution.media(anyString(), any())).thenReturn(new EvolutionApiClient.Downloaded(jpg, "image/jpeg", null));
        when(moderation.check(any(), anyString())).thenReturn(new ModerationClient.Result(true, 0.97));
        upsert("N1", "{\"imageMessage\":{\"mimetype\":\"image/jpeg\"}}");

        verify(evolution, timeout(5000)).sendText(eq(instance), eq(chat), contains("contenido explícito"));
        assertEquals("BLOCKED_NSFW", db.queryForObject("SELECT media_status FROM whatsapp_inbound_message WHERE tenant_id=? AND message_id='N1'", String.class, store));
        assertEquals("BLOCKED_NSFW", inbox.conversation(store, chat).messages().get(0).moderation());
        assertEquals("NSFW", inbox.conversation(store, chat).row().aiPausedReason());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM whatsapp_media_incident WHERE tenant_id=? AND kind='NSFW'", Integer.class, store));
        assertTrue(media.blocked(store, mediaId("N1")));
        verify(vision, never()).analyze(any(), any(), any(), any());
        verify(responder, never()).answer(any(), any(), any(), any(), any(), any());
    }

    @Test
    void explicitImageIsWarnedByCloudApiToo() {
        db.update("INSERT INTO whatsapp_channel_setting(tenant_id,active_provider) VALUES (?, 'CLOUD_API')", store);
        var creds = new CloudApiCredentials(store, "PN1", "W1", "token", "secret");
        when(cloudAccounts.credentials(store)).thenReturn(Optional.of(creds));
        when(cloud.download(creds, "MID-1")).thenReturn(new CloudApiClient.Downloaded(jpg, "image/jpeg"));
        when(cloud.sendText(any(), anyString(), anyString())).thenReturn(SendResult.accepted("wamid.out"));
        when(moderation.check(any(), anyString())).thenReturn(new ModerationClient.Result(true, 0.91));
        db.update("""
                INSERT INTO whatsapp_inbound_message(tenant_id,chat_id,message_id,body,provider,media_type,mime_type,media_status)
                VALUES (?,?,'wamid.in','[Imagen]','CLOUD_API','IMAGE','image/jpeg','PENDING')
                """, store, chat);
        inbox.onInbound(new com.saas.omnicanal.whatsapp.model.InboundMessageEvent(store, chat, "wamid.in", "[Imagen]"));

        pipeline.download(new InboundMediaEvent(store, chat, "wamid.in", WhatsAppProvider.CLOUD_API, MediaKind.IMAGE, "image/jpeg", null, "MID-1"));
        verify(cloud).sendText(eq(creds), eq("51955544433"), contains("contenido explícito"));
        verify(vision, never()).analyze(any(), any(), any(), any());
    }

    @Test
    void offContextImageAppliesTheOffTopicRuleWithoutCallingTheChatModel() {
        when(evolution.media(anyString(), any())).thenReturn(new EvolutionApiClient.Downloaded(jpg, "image/jpeg", null));
        when(moderation.check(any(), anyString())).thenReturn(new ModerationClient.Result(false, 0.01));
        when(vision.analyze(any(), anyString(), any(), any())).thenReturn(new VisionClient.Analysis(false, "", "", "", "", "Un plato de ceviche"));
        upsert("F1", "{\"imageMessage\":{\"mimetype\":\"image/jpeg\"}}");

        verify(evolution, timeout(5000)).sendText(eq(instance), eq(chat), contains("Solo puedo ayudarte"));
        assertEquals(1, inbox.conversation(store, chat).row().offTopicCount());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM whatsapp_media_incident WHERE tenant_id=? AND kind='OFF_CONTEXT'", Integer.class, store));
        verify(responder, never()).answer(any(), any(), any(), any(), any(), any());
    }

    @Test
    void validImageFindsSimilarProductsAndPassesOnlyTextToTheAssistant() {
        UUID polo = UUID.randomUUID(), jean = UUID.randomUUID();
        db.update("INSERT INTO product(id,tenant_id,name,category,subcategory) VALUES (?,?,'Polo Retro 90','Polos','Retro'), (?,?,'Jean Mom','Jeans',NULL)",
                polo, store, jean, store);
        db.update("INSERT INTO product_variant(product_id,tenant_id,sku,size,color,price,stock_physical) VALUES (?,?,'PR-M','M','Negro',59.90,3), (?,?,'JM-28','28','Azul',119,2)",
                polo, store, jean, store);
        when(evolution.media(anyString(), any())).thenReturn(new EvolutionApiClient.Downloaded(jpg, "image/jpeg", null));
        when(moderation.check(any(), anyString())).thenReturn(new ModerationClient.Result(false, 0.02));
        when(vision.analyze(any(), anyString(), any(), any()))
                .thenReturn(new VisionClient.Analysis(true, "polo", "retro", "negro", "estampado vintage", "Polo negro con estampado retro"));
        upsert("V1", "{\"imageMessage\":{\"mimetype\":\"image/jpeg\",\"caption\":\"¿Tienen algo así?\"}}");

        verify(responder, timeout(5000)).answer(eq(store), any(), anyString(), anyList(), any(), contains("Polo Retro 90"));
        var message = inbox.conversation(store, chat).messages().get(0);
        assertEquals("SAFE", message.moderation());
        assertEquals("polo", message.vision().path("tipoPrenda").asText());
        assertEquals("Polo Retro 90", message.vision().path("coincidencias").path(0).path("nombre").asText());
        assertEquals(1, message.vision().path("coincidencias").size(), "el jean azul no se parece");
        // Releer el chat usa la caché: no se vuelve a llamar a ningún servicio externo.
        inbox.conversation(store, chat);
        pipeline.process(event("V1", MediaKind.IMAGE), mediaId("V1"), jpg, "image/jpeg");
        verify(moderation, times(1)).check(any(), anyString());
        verify(vision, times(1)).analyze(any(), anyString(), any(), any());
    }

    private InboundMediaEvent event(String id, MediaKind kind) {
        return new InboundMediaEvent(store, chat, id, WhatsAppProvider.EVOLUTION, kind, null, null, null);
    }

    private UUID mediaId(String message) {
        for (int i = 0; i < 100; i++) {
            String url = db.queryForObject("SELECT media_url FROM whatsapp_inbound_message WHERE tenant_id=? AND message_id=?", String.class, store, message);
            if (url != null) return UUID.fromString(url.substring(7));
            try { Thread.sleep(50); } catch (InterruptedException e) { throw new IllegalStateException(e); }
        }
        throw new AssertionError("El archivo no se guardó");
    }

    private void upsert(String id, String message) {
        String event = """
                {"event":"messages.upsert","instance":"%s","data":{"key":{"id":"%s","remoteJid":"%s","fromMe":false},"message":%s}}
                """.formatted(instance, id, chat, message);
        webhooks.receive(store, webhooks.credential(store), json.readTree(event));
    }
}
