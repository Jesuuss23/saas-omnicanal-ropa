package com.saas.omnicanal.assistant.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.saas.omnicanal.assistant.model.AssistantModels.Answer;
import com.saas.omnicanal.assistant.repository.AssistantRepository;
import com.saas.omnicanal.inbox.model.InboxViews.Status;
import com.saas.omnicanal.inbox.service.InboxService;
import com.saas.omnicanal.whatsapp.model.InboundMessageEvent;
import com.saas.omnicanal.whatsapp.service.WhatsAppReplyService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Dominio estricto (fuera de tema → aviso, luego pausa sin escalar) y agrupación de mensajes seguidos. */
@SpringBootTest
class AssistantRulesTests {
    private static final Set<String> OFF = Set.of("¿Quién ganó la guerra del Pacífico?", "Cuéntame un chiste", "¿Va a llover mañana?",
            "Hazme la tarea de mate");

    @Autowired JdbcTemplate db;
    @Autowired AssistantService assistant;
    @Autowired AssistantRepository repo;
    @Autowired InboxService inbox;
    @MockitoBean AssistantResponder responder;
    @MockitoBean WhatsAppReplyService replies;

    @Test
    void offTopicWarnsOnceThenPausesWithoutEscalating() {
        UUID store = store();
        String chat = chat(store);
        inbound(store, chat, "o1", "¿Quién ganó la guerra del Pacífico?");
        assertEquals("SENT", status(store, chat, "o1"));
        assertEquals(AssistantService.offTopicWarning("Tienda Reglas"), reply(store, chat, "o1"));
        assertEquals(1, conversation(store, chat).offTopicCount());

        // Una consulta válida después del aviso borra el contador.
        inbound(store, chat, "o2", "¿Tienen polos negros?");
        assertEquals("Sí, tenemos polos negros.", reply(store, chat, "o2"));
        assertEquals(0, conversation(store, chat).offTopicCount());

        inbound(store, chat, "o3", "Cuéntame un chiste");
        assertEquals(AssistantService.offTopicWarning("Tienda Reglas"), reply(store, chat, "o3"));
        inbound(store, chat, "o4", "¿Va a llover mañana?");
        assertEquals(AssistantService.OFF_TOPIC_PAUSE, reply(store, chat, "o4"));
        var row = conversation(store, chat);
        assertFalse(row.aiEnabled());
        assertEquals("OFF_TOPIC", row.aiPausedReason());
        assertEquals(Status.OPEN, row.status(), "no se deriva a una persona");
        assertEquals(0, db.queryForObject("SELECT count(*) FROM email_log WHERE recipient=? AND template='conversation_escalated'",
                Integer.class, store + "@reglas.test"));

        // Pausada: se ignora antes de llamar al modelo (0 tokens).
        int calls = mockingDetails(responder).getInvocations().stream().filter(i -> i.getMethod().getName().equals("answer")).toList().size();
        inbound(store, chat, "o5", "¿Tienen jeans?");
        assertEquals("SKIPPED", status(store, chat, "o5"));
        assertEquals("off_topic_paused", db.queryForObject("SELECT skip_reason FROM ai_reply WHERE tenant_id=? AND message_id='o5'", String.class, store));
        verify(responder, times(calls)).answer(any(), any(), any(), any(), any(), any());

        // El equipo reactiva la IA: vuelve a responder con el contador en cero.
        inbox.setAi(store, chat, true);
        assertNull(conversation(store, chat).aiPausedReason());
        inbound(store, chat, "o6", "¿Tienen polos negros?");
        assertEquals("SENT", status(store, chat, "o6"));

        // Pausada otra vez y cerrada por el equipo: cuando el cliente vuelve a escribir, la conversación se reinicia.
        inbound(store, chat, "o7", "Cuéntame un chiste");
        inbound(store, chat, "o8", "Hazme la tarea de mate");
        assertEquals("OFF_TOPIC", conversation(store, chat).aiPausedReason());
        inbox.setStatus(store, chat, Status.CLOSED);
        inbound(store, chat, "o9", "¿Tienen polos negros?");
        assertEquals("SENT", status(store, chat, "o9"));
        assertTrue(conversation(store, chat).aiEnabled());

        // Tomar y devolver también reinicia.
        inbound(store, chat, "o10", "Cuéntame un chiste");
        inbound(store, chat, "o11", "Cuéntame un chiste");
        inbox.take(store, chat, "equipo@reglas.test");
        inbox.handback(store, chat, null);
        var after = conversation(store, chat);
        assertTrue(after.aiEnabled());
        assertEquals(0, after.offTopicCount());
        assertNull(after.aiPausedReason());
    }

    @Test
    void consecutiveMessagesGetOneReply() {
        UUID store = store();
        String chat = chat(store);
        for (var m : List.of(new String[]{"b1", "Hola"}, new String[]{"b2", "que hay"}, new String[]{"b3", "me interesa"})) {
            save(store, chat, m[0], m[1]);
            assertTrue(assistant.receive(new InboundMessageEvent(store, chat, m[0], m[1])));
        }
        assistant.flush(store, chat);
        verify(responder, times(1)).answer(eq(store), any(), anyString(), eq(List.of()), any(), eq("Hola\nque hay\nme interesa"));
        verify(replies, times(1)).replyAsAssistant(eq(store), any(), eq(chat), anyString());
        assertEquals("SENT", status(store, chat, "b3"));
        assertEquals("BATCHED", status(store, chat, "b1"));
        assertEquals("b3", db.queryForObject("SELECT batch_of FROM ai_reply WHERE tenant_id=? AND message_id='b2'", String.class, store));
        // Nada más en espera: un segundo flush no responde de nuevo.
        assistant.flush(store, chat);
        verify(responder, times(1)).answer(any(), any(), any(), any(), any(), any());
    }

    @Test
    void timerWaitsForTheLastMessageAndTwoFlushesNeverReplyTwice() throws Exception {
        UUID store = store();
        String chat = chat(store);
        // Webhooks reales (onInbound) a 100 ms entre sí con ventana de 300 ms: una sola respuesta, después del último.
        for (var m : List.of(new String[]{"t1", "Hola"}, new String[]{"t2", "busco un jean"}, new String[]{"t3", "talla 30"})) {
            save(store, chat, m[0], m[1]);
            assistant.onInbound(new InboundMessageEvent(store, chat, m[0], m[1]));
            Thread.sleep(100);
        }
        verify(responder, never()).answer(any(), any(), any(), any(), any(), any());
        long deadline = System.currentTimeMillis() + 5000;
        while (!"SENT".equals(status(store, chat, "t3")) && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertEquals("SENT", status(store, chat, "t3"));
        verify(responder, times(1)).answer(eq(store), any(), anyString(), anyList(), any(), eq("Hola\nbusco un jean\ntalla 30"));

        // Dos hilos vacían el mismo chat a la vez: el UPDATE atómico entrega los mensajes a uno solo.
        save(store, chat, "t4", "¿y en negro?");
        assertTrue(assistant.receive(new InboundMessageEvent(store, chat, "t4", "¿y en negro?")));
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        var done = new AtomicInteger();
        for (int i = 0; i < 2; i++) pool.execute(() -> {
            try {
                start.await();
                assistant.flush(store, chat);
                done.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(2, done.get());
        verify(responder, times(1)).answer(any(), any(), any(), any(), any(), eq("¿y en negro?"));
        verify(replies, times(2)).replyAsAssistant(eq(store), any(), eq(chat), anyString());
    }

    @Test
    void testerFollowsTheSameRules() {
        UUID store = store();
        var first = assistant.test(store, List.of(), "Cuéntame un chiste", 0);
        assertTrue(first.offTopic());
        assertFalse(first.paused());
        assertFalse(first.escalate());
        assertEquals(AssistantService.offTopicWarning("Tienda Reglas"), first.text());
        var second = assistant.test(store, List.of(), "¿Va a llover mañana?", 1);
        assertTrue(second.paused());
        assertEquals(AssistantService.OFF_TOPIC_PAUSE, second.text());
        clearInvocations(responder);
        var ignored = assistant.test(store, List.of(), "¿Tienen polos negros?", 2);
        assertTrue(ignored.paused());
        assertEquals("", ignored.text());
        verify(responder, never()).answer(any(), any(), any(), any(), any(), any());
    }

    @Test
    void debouncerRestartsTheWaitAndNeverOverlaps() throws Exception {
        var runs = new AtomicInteger();
        var concurrent = new AtomicInteger();
        var maxConcurrent = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(4);
        try (var debouncer = new Debouncer(pool, 200)) {
            Runnable task = () -> {
                maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                concurrent.decrementAndGet();
                runs.incrementAndGet();
            };
            for (int i = 0; i < 4; i++) {
                debouncer.submit("chat", task);
                Thread.sleep(80);
            }
            assertEquals(0, runs.get(), "todavía dentro de la ventana");
            Thread.sleep(400);
            // Llega otro mensaje mientras la primera tarea corre: espera a que termine.
            debouncer.submit("chat", task);
            Thread.sleep(1200);
            assertEquals(2, runs.get());
            assertEquals(1, maxConcurrent.get());
        } finally {
            pool.shutdownNow();
        }
    }

    private UUID store() {
        UUID store = UUID.randomUUID();
        db.update("INSERT INTO tenant(id,name) VALUES (?, 'Tienda Reglas')", store);
        db.update("INSERT INTO tenant_owner(tenant_id,email,password_hash) VALUES (?,?, 'x')", store, store + "@reglas.test");
        db.update("INSERT INTO tenant_subscription(tenant_id,plan_code,status,trial_ends_at) VALUES (?, 'TRIAL', 'TRIAL', now() + interval '5 days')", store);
        assertTrue(repo.saveSettings(store, 0, true, "", 10));
        when(responder.configured()).thenReturn(true);
        when(replies.replyAsAssistant(any(), any(), anyString(), anyString())).thenReturn("ACCEPTED");
        when(responder.answer(eq(store), any(), anyString(), anyList(), any(), anyString())).thenAnswer(inv -> {
            String message = inv.getArgument(5);
            Thread.sleep(50);
            return OFF.contains(message)
                    ? new Answer("Claro, te cuento...", true, "No sé", "x", List.of("fueraDeTema"), true, false)
                    : new Answer("Sí, tenemos polos negros.", false, null, null, List.of("buscarPrendas"));
        });
        return store;
    }

    private static String chat(UUID store) {
        return "51966" + String.valueOf(Math.abs(store.getLeastSignificantBits())).substring(0, 6) + "@s.whatsapp.net";
    }

    private void save(UUID store, String chat, String id, String body) {
        db.update("INSERT INTO whatsapp_inbound_message(tenant_id,chat_id,message_id,body) VALUES (?,?,?,?)", store, chat, id, body);
        inbox.onInbound(new InboundMessageEvent(store, chat, id, body));
    }

    private void inbound(UUID store, String chat, String id, String body) {
        save(store, chat, id, body);
        assistant.handle(new InboundMessageEvent(store, chat, id, body));
    }

    private com.saas.omnicanal.inbox.model.InboxViews.Row conversation(UUID store, String chat) {
        return inbox.conversation(store, chat).row();
    }

    private String status(UUID store, String chat, String id) {
        return db.queryForObject("SELECT status FROM ai_reply WHERE tenant_id=? AND chat_id=? AND message_id=?", String.class, store, chat, id);
    }

    private String reply(UUID store, String chat, String id) {
        return db.queryForObject("SELECT reply FROM ai_reply WHERE tenant_id=? AND chat_id=? AND message_id=?", String.class, store, chat, id);
    }
}
