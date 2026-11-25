package com.saas.omnicanal.whatsapp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.saas.omnicanal.assistant.service.AssistantService;
import com.saas.omnicanal.inbox.service.InboxService;
import com.saas.omnicanal.whatsapp.model.CloudApiCredentials;
import com.saas.omnicanal.whatsapp.model.InboundMediaEvent;
import com.saas.omnicanal.whatsapp.model.InboundMessageEvent;
import com.saas.omnicanal.whatsapp.model.MediaKind;
import com.saas.omnicanal.whatsapp.model.SendResult;
import com.saas.omnicanal.whatsapp.model.WhatsAppProvider;
import com.saas.omnicanal.whatsapp.repository.WhatsAppMediaRepository;
import com.saas.omnicanal.whatsapp.service.CloudApiClient;
import com.saas.omnicanal.whatsapp.service.CloudApiService;
import com.saas.omnicanal.whatsapp.service.EvolutionApiClient;
import com.saas.omnicanal.whatsapp.service.EvolutionWebhookService;
import com.saas.omnicanal.whatsapp.service.WhatsAppMediaService;
import com.saas.omnicanal.whatsapp.service.WhatsAppReplyService;
import com.saas.omnicanal.whatsapp.service.WhatsAppReplyService.Outgoing;
import java.nio.charset.StandardCharsets;
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
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Archivos de WhatsApp: voz, imagen y video entrantes (Evolution y Cloud API) y envío según el plan. */
@SpringBootTest
class WhatsAppMediaTests {
    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry props) {
        props.add("EVOLUTION_WEBHOOK_SECRET", () -> "secreto-de-prueba");
    }

    @Autowired JdbcTemplate db;
    @Autowired EvolutionWebhookService webhooks;
    @Autowired WhatsAppMediaService mediaService;
    @Autowired WhatsAppMediaRepository media;
    @Autowired WhatsAppReplyService replies;
    @Autowired InboxService inbox;
    @Autowired ObjectMapper json;
    @MockitoBean EvolutionApiClient evolution;
    @MockitoBean CloudApiClient cloud;
    @MockitoBean CloudApiService cloudAccounts;
    @MockitoBean AssistantService assistant;

    UUID store;
    final String chat = "51987654321@s.whatsapp.net";

    @BeforeEach
    void setUp() {
        store = UUID.randomUUID();
        db.update("INSERT INTO tenant(id,name,whatsapp_instance_id) VALUES (?,?,?)", store, "Tienda Media", EvolutionApiClient.instanceName(store));
        db.update("INSERT INTO tenant_subscription(tenant_id,plan_code,status,trial_ends_at) VALUES (?, 'TRIAL', 'TRIAL', now() + interval '5 days')", store);
        when(evolution.configured()).thenReturn(true);
    }

    @Test
    void evolutionVoiceNoteIsSavedDownloadedAndShownInTheInbox() throws Exception {
        byte[] audio = "OggS-audio-de-prueba".getBytes(StandardCharsets.UTF_8);
        when(evolution.media(anyString(), any())).thenReturn(new EvolutionApiClient.Downloaded(audio, "audio/ogg; codecs=opus", null));
        String event = """
                {"event":"messages.upsert","instance":"%s","data":{"key":{"id":"V1","remoteJid":"%s","fromMe":false},
                 "message":{"audioMessage":{"mimetype":"audio/ogg; codecs=opus","seconds":4,"ptt":true}}}}
                """.formatted(EvolutionApiClient.instanceName(store), chat);
        assertEquals(1, webhooks.receive(store, webhooks.credential(store), json.readTree(event)).get("inserted"));
        // La IA recibe el marcador, no un mensaje vacío.
        verify(assistant).onInbound(new InboundMessageEvent(store, chat, "V1", "[Nota de voz]", null, InboundMessageEvent.Delivery.INBOX));

        String status = waitForMedia(chat, "V1");
        assertEquals("STORED", status);
        var message = inbox.conversation(store, chat).messages().get(0);
        assertEquals("AUDIO", message.mediaType());
        assertEquals("audio/ogg", message.mimeType());
        assertTrue(message.mediaUrl().startsWith("/media/"));
        var stored = media.read(store, UUID.fromString(message.mediaUrl().substring(7))).orElseThrow();
        assertArrayEquals(audio, stored.content());
        // Otra tienda no puede leer el archivo.
        assertTrue(media.read(UUID.randomUUID(), UUID.fromString(message.mediaUrl().substring(7))).isEmpty());
    }

    @Test
    void evolutionImageKeepsItsCaptionAndFailedDownloadsAreMarked() throws Exception {
        when(evolution.media(anyString(), any())).thenThrow(new IllegalStateException("Evolution caído"));
        String event = """
                {"event":"messages.upsert","instance":"%s","data":{"key":{"id":"I1","remoteJid":"%s","fromMe":false},
                 "message":{"imageMessage":{"mimetype":"image/jpeg","caption":"¿Tienen este modelo?"}}}}
                """.formatted(EvolutionApiClient.instanceName(store), chat);
        webhooks.receive(store, webhooks.credential(store), json.readTree(event));
        verify(assistant).onInbound(new InboundMessageEvent(store, chat, "I1", "¿Tienen este modelo?", null, InboundMessageEvent.Delivery.INBOX));
        assertEquals("FAILED", waitForMedia(chat, "I1"));
        var message = inbox.conversation(store, chat).messages().get(0);
        assertEquals("IMAGE", message.mediaType());
        assertEquals("¿Tienen este modelo?", message.caption());
        assertNull(message.mediaUrl());
    }

    @Test
    void cloudApiVideoIsDownloadedWithTheStoreCredentials() {
        var creds = new CloudApiCredentials(store, "PN1", "W1", "token", "secret");
        when(cloudAccounts.credentials(store)).thenReturn(Optional.of(creds));
        byte[] video = new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
        when(cloud.download(creds, "MEDIA-9")).thenReturn(new CloudApiClient.Downloaded(video, "video/mp4"));
        db.update("""
                INSERT INTO whatsapp_inbound_message(tenant_id,chat_id,message_id,body,provider,media_type,mime_type,media_status)
                VALUES (?,?,?,'[Video]','CLOUD_API','VIDEO','video/mp4','PENDING')
                """, store, chat, "wamid.1");
        mediaService.download(new InboundMediaEvent(store, chat, "wamid.1", WhatsAppProvider.CLOUD_API, MediaKind.VIDEO, "video/mp4", null, "MEDIA-9"));
        assertEquals("STORED", db.queryForObject("SELECT media_status FROM whatsapp_inbound_message WHERE tenant_id=? AND message_id='wamid.1'", String.class, store));
    }

    @Test
    void imagesAndVideosNeedTheTopPlanButPdfsDoNot() {
        db.update("INSERT INTO whatsapp_inbound_message(tenant_id,chat_id,message_id,body,provider) VALUES (?,?,'C1','Hola','EVOLUTION')", store, chat);
        db.update("UPDATE tenant SET whatsapp_instance_id=? WHERE id=?", EvolutionApiClient.instanceName(store), store);
        when(evolution.sendMedia(anyString(), anyString(), anyString(), anyString(), any(), any(), anyString())).thenReturn(SendResult.accepted("M1"));
        byte[] jpg = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0, 0, 0, 0, 0, 0, 0, 0, 0};

        var error = assertThrows(ResponseStatusException.class, () ->
                replies.replyMedia(store, UUID.randomUUID(), chat, new Outgoing(MediaKind.IMAGE, "image/jpeg", "foto.jpg", jpg, "Mira")));
        assertEquals(402, error.getStatusCode().value());
        assertTrue(error.getReason().contains("plan Premium"));
        assertFalse(replies.canSendMedia(store));

        // El PDF del catálogo se envía con cualquier plan.
        byte[] pdf = "%PDF-1.4 prueba".getBytes(StandardCharsets.US_ASCII);
        assertEquals("ACCEPTED", replies.replyMedia(store, UUID.randomUUID(), chat, new Outgoing(MediaKind.DOCUMENT, "application/pdf", "catalogo.pdf", pdf, "")));
        verify(evolution).sendMedia(eq(EvolutionApiClient.instanceName(store)), eq(chat), eq("document"), eq("application/pdf"), eq("catalogo.pdf"), eq(pdf), eq(""));

        db.update("UPDATE tenant_subscription SET plan_code='PREMIUM', status='ACTIVE', current_period_end=now()+interval '20 days' WHERE tenant_id=?", store);
        assertTrue(replies.canSendMedia(store));
        assertEquals("ACCEPTED", replies.replyMedia(store, UUID.randomUUID(), chat, new Outgoing(MediaKind.IMAGE, "image/jpeg", "foto.jpg", jpg, "Mira")));
        var last = db.queryForMap("SELECT body, media_type, media_url, author FROM whatsapp_manual_reply WHERE tenant_id=? AND provider_id='M1' ORDER BY created_at DESC LIMIT 1", store);
        assertEquals("IMAGE", last.get("media_type"));
        assertEquals("Mira", last.get("body"));
        assertEquals("HUMAN", last.get("author"));
        assertNotNull(last.get("media_url"));
    }

    private String waitForMedia(String chat, String id) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            String status = db.queryForObject("SELECT media_status FROM whatsapp_inbound_message WHERE tenant_id=? AND chat_id=? AND message_id=?",
                    String.class, store, chat, id);
            if (!"PENDING".equals(status)) return status;
            Thread.sleep(50);
        }
        return "PENDING";
    }
}
