package com.saas.omnicanal.whatsapp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.saas.omnicanal.assistant.service.AssistantService;
import com.saas.omnicanal.inbox.service.InboxService;
import com.saas.omnicanal.whatsapp.model.InboundMessageEvent;
import com.saas.omnicanal.whatsapp.model.SendResult;
import com.saas.omnicanal.whatsapp.service.EvolutionApiClient;
import com.saas.omnicanal.whatsapp.service.EvolutionWebhookService;
import com.saas.omnicanal.whatsapp.service.WhatsAppReplyService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Evolution API 2.3.x (Baileys 7): chats @lid con remoteJidAlt y mensajes fromMe enviados desde el celular. */
@SpringBootTest
class EvolutionWebhookTests {
    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry props) {
        props.add("EVOLUTION_WEBHOOK_SECRET", () -> "secreto-de-prueba");
    }

    @Autowired JdbcTemplate db;
    @Autowired EvolutionWebhookService webhooks;
    @Autowired WhatsAppReplyService replies;
    @Autowired InboxService inbox;
    @Autowired ObjectMapper json;
    @MockitoBean EvolutionApiClient evolution;
    @MockitoBean AssistantService assistant;

    UUID store;
    String lid;

    @BeforeEach
    void setUp() {
        store = UUID.randomUUID();
        lid = String.valueOf(Math.abs(store.getMostSignificantBits())).substring(0, 15) + "@lid";
        db.update("INSERT INTO tenant(id,name,whatsapp_instance_id) VALUES (?,?,?)", store, "Tienda Evolution", EvolutionApiClient.instanceName(store));
        when(evolution.configured()).thenReturn(true);
    }

    @Test
    void lidChatKeepsItsIdAndShowsThePhoneFromRemoteJidAlt() {
        assertEquals(1, upsert("C1", lid, "51910829620@s.whatsapp.net", false, "Hola, ¿tienen polos?"));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM whatsapp_inbound_message WHERE tenant_id=? AND chat_id=?", Integer.class, store, lid));
        var row = inbox.conversation(store, lid).row();
        assertEquals(lid, row.chat());
        assertEquals("51910829620", row.phone());
        // El cliente dispara la IA con el chat @lid intacto.
        verify(assistant).onInbound(new InboundMessageEvent(store, lid, "C1", "Hola, ¿tienen polos?", "51910829620"));
        // Webhook repetido: no se guarda ni dispara de nuevo.
        assertEquals(0, upsert("C1", lid, "51910829620@s.whatsapp.net", false, "Hola, ¿tienen polos?"));
        verify(assistant, times(1)).onInbound(any());
    }

    @Test
    void fromMeMessagesAreStoredOnceAndNeverTriggerTheAssistant() {
        upsert("C1", lid, "51910829620@s.whatsapp.net", false, "Hola");
        clearInvocations(assistant);

        // Escrito en el celular de la tienda.
        assertEquals(1, upsert("P1", lid, "51910829620@s.whatsapp.net", true, "Te escribo desde el celular"));
        assertEquals(0, upsert("P1", lid, "51910829620@s.whatsapp.net", true, "Te escribo desde el celular"));
        assertEquals("PHONE", db.queryForObject("SELECT author FROM whatsapp_manual_reply WHERE tenant_id=? AND provider_id='P1'", String.class, store));
        var messages = inbox.conversation(store, lid).messages();
        assertEquals("PHONE", messages.get(messages.size() - 1).author());

        // Enviado desde el panel: va a Evolution con el remoteJid tal cual, y su eco (mismo key.id) no se duplica.
        when(evolution.sendText(anyString(), anyString(), anyString())).thenReturn(SendResult.accepted("W1"));
        assertEquals("ACCEPTED", replies.reply(store, UUID.randomUUID(), lid, "Respuesta desde la web"));
        verify(evolution).sendText(EvolutionApiClient.instanceName(store), lid, "Respuesta desde la web");
        assertEquals(0, upsert("W1", lid, "51910829620@s.whatsapp.net", true, "Respuesta desde la web"));

        // El eco llega antes de que el envío devuelva su id: se reconoce por el texto en curso.
        db.update("INSERT INTO whatsapp_manual_reply(tenant_id,request_id,chat_id,body,status,author) VALUES (?,?,?,?,'REVIEW','AI')",
                store, UUID.randomUUID(), lid, "Respuesta de la IA en curso");
        assertEquals(0, upsert("W2", lid, "51910829620@s.whatsapp.net", true, "Respuesta de la IA en curso"));

        assertEquals(3, db.queryForObject("SELECT count(*) FROM whatsapp_manual_reply WHERE tenant_id=?", Integer.class, store));
        verify(assistant, never()).onInbound(any());
    }

    @Test
    void phoneChatsAreSentAsFullJidAndReceiptsAcceptLid() {
        String chat = "51910829620@s.whatsapp.net";
        upsert("C1", "51910829620:12@s.whatsapp.net", null, false, "Hola");
        assertEquals("51910829620", inbox.conversation(store, chat).row().phone());
        when(evolution.sendText(anyString(), anyString(), anyString())).thenReturn(SendResult.accepted("W9"));
        replies.reply(store, UUID.randomUUID(), chat, "Hola, ¿en qué te ayudo?");
        verify(evolution).sendText(eq(EvolutionApiClient.instanceName(store)), eq(chat), anyString());

        upsert("C2", lid, null, false, "Otro cliente");
        when(evolution.sendText(anyString(), anyString(), anyString())).thenReturn(SendResult.accepted("W10"));
        replies.reply(store, UUID.randomUUID(), lid, "Hola");
        assertEquals(1, webhooks.receive(store, webhooks.credential(store), node("""
                {"event":"messages.update","instance":"%s","data":{"keyId":"W10","remoteJid":"%s","fromMe":true,"status":"READ"}}
                """.formatted(EvolutionApiClient.instanceName(store), lid))).get("updated"));
        // Grupos y estados se ignoran.
        assertEquals(0, upsert("G1", "120363025@g.us", null, false, "Hola grupo"));
        assertEquals(0, upsert("S1", "status@broadcast", null, false, "Estado"));
    }

    private int upsert(String id, String remoteJid, String alt, boolean fromMe, String text) {
        String key = "\"id\":\"" + id + "\",\"remoteJid\":\"" + remoteJid + "\",\"fromMe\":" + fromMe
                + (alt == null ? "" : ",\"remoteJidAlt\":\"" + alt + "\",\"addressingMode\":\"lid\"");
        String event = """
                {"event":"messages.upsert","instance":"%s","data":{"key":{%s},"pushName":"Cliente","message":{"conversation":"%s"}}}
                """.formatted(EvolutionApiClient.instanceName(store), key, text);
        return webhooks.receive(store, webhooks.credential(store), node(event)).get("inserted");
    }

    private JsonNode node(String text) {
        return json.readTree(text);
    }
}
