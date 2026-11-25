package com.saas.omnicanal.whatsapp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.saas.omnicanal.assistant.service.AssistantService;
import com.saas.omnicanal.whatsapp.model.SendResult;
import com.saas.omnicanal.whatsapp.service.EvolutionApiClient;
import com.saas.omnicanal.whatsapp.service.EvolutionConnectionService;
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
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Estado de la conexión de Evolution: consulta en vivo, envío sin 409 falso y webhook CONNECTION_UPDATE. */
@SpringBootTest
class EvolutionConnectionTests {
    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry props) {
        props.add("EVOLUTION_WEBHOOK_SECRET", () -> "secreto-de-prueba");
    }

    @Autowired JdbcTemplate db;
    @Autowired WhatsAppReplyService replies;
    @Autowired EvolutionConnectionService connection;
    @Autowired EvolutionWebhookService webhooks;
    @Autowired ObjectMapper json;
    @MockitoBean EvolutionApiClient evolution;
    @MockitoBean AssistantService assistant;

    UUID store;
    String instance;
    final String chat = "51911122233@s.whatsapp.net";

    @BeforeEach
    void setUp() {
        store = UUID.randomUUID();
        instance = EvolutionApiClient.instanceName(store);
        // Vinculada desde Evolution Manager: la tienda todavía no tiene la instancia asignada.
        db.update("INSERT INTO tenant(id,name) VALUES (?, 'Tienda QR')", store);
        db.update("INSERT INTO whatsapp_inbound_message(tenant_id,chat_id,message_id,body,provider) VALUES (?,?,'C1','Hola','EVOLUTION')", store, chat);
        when(evolution.configured()).thenReturn(true);
    }

    @Test
    void replyChecksEvolutionLiveBeforeAnsweringConflict() {
        when(evolution.state(instance)).thenReturn("CONNECTED");
        when(evolution.sendText(anyString(), anyString(), anyString())).thenReturn(SendResult.accepted("W1"));
        assertEquals("ACCEPTED", replies.reply(store, UUID.randomUUID(), chat, "Hola, sí tenemos"));
        verify(evolution).sendText(instance, chat, "Hola, sí tenemos");
        assertEquals(instance, db.queryForObject("SELECT whatsapp_instance_id FROM tenant WHERE id=?", String.class, store));
        assertEquals("CONNECTED", state());
    }

    @Test
    void disconnectedInstanceStillAnswersConflictWithAClearMessage() {
        when(evolution.state(instance)).thenReturn("DISCONNECTED");
        var error = assertThrows(ResponseStatusException.class, () -> replies.reply(store, UUID.randomUUID(), chat, "Hola"));
        assertEquals(409, error.getStatusCode().value());
        assertEquals("Conecta el número de WhatsApp antes de responder.", error.getReason());
        assertEquals("DISCONNECTED", state());
        verify(evolution, never()).sendText(anyString(), anyString(), anyString());
    }

    @Test
    void refreshButtonSavesTheLiveState() {
        when(evolution.state(instance)).thenReturn("CONNECTED");
        var result = connection.status(store);
        assertEquals("CONNECTED", result.state());
        assertNotNull(result.checkedAt());
        assertEquals("CONNECTED", state());
        assertEquals(instance, db.queryForObject("SELECT whatsapp_instance_id FROM tenant WHERE id=?", String.class, store));
    }

    @Test
    void connectionUpdateWebhookStoresTheState() {
        assertEquals(1, update("open"));
        assertEquals("CONNECTED", state());
        assertEquals(instance, db.queryForObject("SELECT whatsapp_instance_id FROM tenant WHERE id=?", String.class, store));
        assertEquals(1, update("close"));
        assertEquals("DISCONNECTED", state());
        assertEquals(1, update("connecting"));
        assertEquals("PAIRING", state());
        // Sin firma válida no cambia nada.
        assertThrows(ResponseStatusException.class, () -> webhooks.receive(store, "firma-falsa", json.readTree(
                "{\"event\":\"connection.update\",\"instance\":\"" + instance + "\",\"data\":{\"state\":\"open\"}}")));
        assertEquals("PAIRING", state());
    }

    private int update(String state) {
        String event = """
                {"event":"connection.update","instance":"%s","data":{"instance":"%s","state":"%s","statusReason":200}}
                """.formatted(instance, instance, state);
        return webhooks.receive(store, webhooks.credential(store), json.readTree(event)).get("updated");
    }

    private String state() {
        return db.queryForObject("SELECT evolution_state FROM whatsapp_channel_setting WHERE tenant_id=?", String.class, store);
    }
}
