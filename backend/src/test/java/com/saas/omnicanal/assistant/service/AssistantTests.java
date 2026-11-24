package com.saas.omnicanal.assistant.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

import com.saas.omnicanal.assistant.model.AssistantModels.Answer;
import com.saas.omnicanal.assistant.model.AssistantModels.StoreFacts;
import com.saas.omnicanal.assistant.repository.AssistantRepository;
import com.saas.omnicanal.catalog.repository.PublicCatalogRepository;
import com.saas.omnicanal.inbox.model.InboxViews.Status;
import com.saas.omnicanal.inbox.service.InboxService;
import com.saas.omnicanal.whatsapp.model.InboundMessageEvent;
import com.saas.omnicanal.whatsapp.service.WhatsAppReplyService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** IA vendedora: interruptores, idempotencia, escalamiento con alerta, devolución con nota y cuota del plan. */
@SpringBootTest
class AssistantTests {
    @Autowired JdbcTemplate db;
    @Autowired AssistantService assistant;
    @Autowired AssistantRepository repo;
    @Autowired InboxService inbox;
    @Autowired PublicCatalogRepository catalog;
    @MockitoBean AssistantResponder responder;
    @MockitoBean WhatsAppReplyService replies;

    @Test
    void repliesEscalatesAndHandsBack() {
        UUID store = UUID.randomUUID();
        String chat = "51955" + String.valueOf(Math.abs(store.getMostSignificantBits())).substring(0, 6) + "@s.whatsapp.net";
        db.update("INSERT INTO tenant(id,name) VALUES (?, 'Tienda IA')", store);
        db.update("INSERT INTO tenant_owner(tenant_id,email,password_hash) VALUES (?,?, 'x')", store, store + "@ia.test");
        db.update("INSERT INTO tenant_subscription(tenant_id,plan_code,status,trial_ends_at) VALUES (?, 'TRIAL', 'TRIAL', now() + interval '5 days')", store);
        when(responder.configured()).thenReturn(true);
        when(replies.replyAsAssistant(any(), any(), anyString(), anyString())).thenReturn("ACCEPTED");
        when(responder.answer(eq(store), any(), anyString(), anyList(), any(), anyString()))
                .thenReturn(new Answer("Tenemos el polo negro en M a S/ 49.90.", false, null, null, List.of("buscarPrendas")));

        // Tienda con la IA apagada (valor inicial): no responde.
        inbound(store, chat, "m1", "Hola, ¿tienen polos?");
        assertEquals("store_off", skip(store, chat, "m1"));
        verify(responder, never()).answer(any(), any(), any(), any(), any(), any());

        assertTrue(repo.saveSettings(store, 0, true, "Tutea al cliente.", 10));
        inbound(store, chat, "m2", "¿Tienen polos negros en M?");
        assertEquals("SENT", status(store, chat, "m2"));
        assertEquals("buscarPrendas", db.queryForObject("SELECT tools_used FROM ai_reply WHERE tenant_id=? AND message_id='m2'", String.class, store));
        // El mismo mensaje reenviado por el proveedor no se responde dos veces.
        assistant.handle(new InboundMessageEvent(store, chat, "m2", "¿Tienen polos negros en M?"));
        verify(responder, times(1)).answer(any(), any(), any(), any(), any(), any());

        // Pedido explícito de una persona: escala sin pasar por el modelo y avisa al dueño una sola vez.
        inbound(store, chat, "m3", "Quiero hablar con una persona por favor");
        assertEquals("ESCALATED", status(store, chat, "m3"));
        var conversation = inbox.conversation(store, chat);
        assertEquals(Status.ESCALATED, conversation.row().status());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM email_log WHERE recipient=? AND template='conversation_escalated'",
                Integer.class, store + "@ia.test"));
        verify(responder, times(1)).answer(any(), any(), any(), any(), any(), any());

        // Escalada: la IA calla.
        inbound(store, chat, "m4", "¿Hola?");
        assertEquals("escalated", skip(store, chat, "m4"));

        // El equipo la devuelve con una nota que la IA recibe como contexto.
        inbox.handback(store, chat, "Ya le cambiamos la talla, solo confirma el envío.");
        inbound(store, chat, "m5", "¿Cuándo llega?");
        assertEquals("SENT", status(store, chat, "m5"));
        verify(responder).answer(eq(store), any(), eq("Tutea al cliente."), anyList(), eq("Ya le cambiamos la talla, solo confirma el envío."), eq("¿Cuándo llega?"));

        // Interruptor por conversación.
        inbox.setAi(store, chat, false);
        inbound(store, chat, "m6", "Gracias");
        assertEquals("conversation_off", skip(store, chat, "m6"));
        inbox.setAi(store, chat, true);

        // La IA pide escalar (reclamo detectado por el modelo).
        when(responder.answer(eq(store), any(), anyString(), anyList(), isNull(), eq("El polo vino roto")))
                .thenReturn(new Answer("Lo siento mucho, te paso con el equipo.", true, "Reclamo por prenda dañada", "Polo llegó roto.", List.of("escalar")));
        inbox.handback(store, chat, null);
        inbound(store, chat, "m7", "El polo vino roto");
        assertEquals("ESCALATED", status(store, chat, "m7"));
        assertEquals("Polo llegó roto.", inbox.conversation(store, chat).summary());
        inbox.handback(store, chat, null);

        // Cuota del plan agotada.
        db.update("INSERT INTO tenant_entitlement_override(tenant_id,entitlement_key,enabled,limit_value,reason) VALUES (?, ?, true, 2, 'prueba')",
                store, AssistantService.QUOTA);
        inbound(store, chat, "m8", "Otra consulta");
        assertEquals("quota", skip(store, chat, "m8"));
        assertEquals(2, assistant.status(store).usedThisMonth());

        // Interruptor global de la plataforma.
        db.update("DELETE FROM tenant_entitlement_override WHERE tenant_id=?", store);
        repo.setPlatformEnabled(false, "prueba");
        try {
            inbound(store, chat, "m9", "¿Siguen ahí?");
            assertEquals("platform_off", skip(store, chat, "m9"));
        } finally {
            repo.setPlatformEnabled(true, "prueba");
        }
    }

    @Test
    void toolsOnlySeeTheirOwnStore() {
        UUID store = UUID.randomUUID(), other = UUID.randomUUID(), product = UUID.randomUUID(), foreign = UUID.randomUUID();
        db.update("INSERT INTO tenant(id,name) VALUES (?, 'Con stock'), (?, 'Otra')", store, other);
        db.update("INSERT INTO product(id,tenant_id,name,category) VALUES (?,?,'Polo IA','Polos'), (?,?,'Polo ajeno','Polos')", product, store, foreign, other);
        db.update("INSERT INTO product_variant(product_id,tenant_id,sku,size,color,price,stock_physical) VALUES (?,?,'IA-M','M','Negro',49.90,3)", product, store);
        db.update("INSERT INTO product_variant(product_id,tenant_id,sku,size,color,price,stock_physical) VALUES (?,?,'AJ-M','M','Negro',10,3)", foreign, other);
        var tools = new StoreTools(store, catalog, new StoreFacts("Con stock", null, null, null, false, "http://x/tienda/" + store));
        var items = tools.buscarPrendas("polo", "M", "");
        assertEquals(1, items.size());
        assertEquals("Polo IA", items.get(0).prenda());
        assertEquals(0, new BigDecimal("49.90").compareTo(items.get(0).precio()));
        assertTrue(items.get(0).enlace().startsWith("http://x/tienda/" + store + "?variant="));
        assertEquals("Listo. Dile al cliente que una persona del equipo le responderá pronto.", tools.escalar("Cambio", "Quiere cambiar talla"));
        assertEquals(List.of("buscarPrendas", "escalar"), tools.used);
        assertTrue(GeminiResponder.system(new StoreFacts("Con stock", null, null, null, false, "u"), "Sé breve", "Nota").contains("Sé breve"));
    }

    private void inbound(UUID store, String chat, String id, String body) {
        db.update("INSERT INTO whatsapp_inbound_message(tenant_id,chat_id,message_id,body) VALUES (?,?,?,?)", store, chat, id, body);
        var event = new InboundMessageEvent(store, chat, id, body);
        inbox.onInbound(event);
        assistant.handle(event);
    }

    private String status(UUID store, String chat, String id) {
        return db.queryForObject("SELECT status FROM ai_reply WHERE tenant_id=? AND chat_id=? AND message_id=?", String.class, store, chat, id);
    }

    private String skip(UUID store, String chat, String id) {
        assertEquals("SKIPPED", status(store, chat, id));
        return db.queryForObject("SELECT skip_reason FROM ai_reply WHERE tenant_id=? AND chat_id=? AND message_id=?", String.class, store, chat, id);
    }
}
