package com.saas.omnicanal.assistant.service;

import com.saas.omnicanal.assistant.model.AssistantModels.Answer;
import com.saas.omnicanal.assistant.model.AssistantModels.Settings;
import com.saas.omnicanal.assistant.model.AssistantModels.Status;
import com.saas.omnicanal.assistant.model.AssistantModels.Turn;
import com.saas.omnicanal.assistant.repository.AssistantRepository;
import com.saas.omnicanal.inbox.model.InboxViews.Message;
import com.saas.omnicanal.inbox.repository.ConversationRepository;
import com.saas.omnicanal.inbox.service.InboxService;
import com.saas.omnicanal.subscription.service.EntitlementService;
import com.saas.omnicanal.whatsapp.model.ChatIds;
import com.saas.omnicanal.whatsapp.model.InboundMessageEvent;
import com.saas.omnicanal.whatsapp.service.WhatsAppReplyService;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;

/**
 * IA vendedora. Cada mensaje entrante pasa por tres interruptores (plataforma, tienda y conversación), la cuota
 * mensual del plan y el estado de la conversación antes de llegar al modelo. Todo queda en {@code ai_reply}.
 */
@Service
public class AssistantService {
    public static final String QUOTA = "ai.responses.monthly";
    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
    /** Pedidos explícitos de una persona: se escala sin pasar por el modelo. */
    private static final Pattern HUMAN = Pattern.compile(
            "\\b(humano|una persona|asesor|asesora|encargad[oa]|hablar con alguien|reclamo|queja|libro de reclamaciones)\\b");
    private static final String HANDOFF = "Gracias por escribirnos. Te comunico con una persona del equipo, que te responderá pronto.";

    private final AssistantRepository repo;
    private final ConversationRepository conversations;
    private final InboxService inbox;
    private final EntitlementService entitlements;
    private final AssistantResponder responder;
    private final WhatsAppReplyService replies;
    private final String baseUrl;
    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

    public AssistantService(AssistantRepository repo, ConversationRepository conversations, InboxService inbox,
                            EntitlementService entitlements, AssistantResponder responder, WhatsAppReplyService replies,
                            @Value("${app.base-url:http://localhost:3000}") String baseUrl) {
        this.repo = repo;
        this.conversations = conversations;
        this.inbox = inbox;
        this.entitlements = entitlements;
        this.responder = responder;
        this.replies = replies;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("ia-vendedora-");
        executor.initialize();
    }

    // --- Panel ---

    public Settings settings(UUID tenant) {
        return repo.settings(tenant);
    }

    public Settings save(UUID tenant, long revision, boolean enabled, String instructions, int sla) {
        if (!repo.saveSettings(tenant, revision, enabled, instructions.strip(), sla))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Otra persona cambió la configuración. Recarga la página.");
        return repo.settings(tenant);
    }

    public Status status(UUID tenant) {
        var quota = entitlements.get(tenant, QUOTA);
        return new Status(responder.configured(), repo.platformEnabled(), quota.enabled(), repo.sentThisMonth(tenant), quota.limit());
    }

    /** Prueba desde el panel: responde sin enviar nada al cliente ni contar para la cuota. */
    public Answer test(UUID tenant, List<Turn> history, String message) {
        if (!responder.configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "La IA no está configurada en el servidor.");
        var facts = repo.facts(tenant, baseUrl).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (HUMAN.matcher(normalize(message)).find())
            return new Answer(HANDOFF, true, "Pidió hablar con una persona", message, List.of());
        try {
            return responder.answer(tenant, facts, repo.settings(tenant).instructions(), history, null, message);
        } catch (RuntimeException ex) {
            log.error("Prueba de IA falló en {}: {}", tenant, GeminiResponder.describe(ex));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "El modelo no respondió. Intenta de nuevo en un momento.");
        }
    }

    // --- Mensajes entrantes ---

    /**
     * Corre después de confirmar el mensaje (fuera de la transacción del webhook) en un ejecutor propio, así el
     * proveedor recibe su 200 de inmediato y una respuesta lenta del modelo no bloquea otros webhooks.
     */
    @TransactionalEventListener(fallbackExecution = true)
    public void onInbound(InboundMessageEvent event) {
        executor.execute(() -> {
            try {
                handle(event);
            } catch (RuntimeException ex) {
                log.error("IA vendedora: error procesando {} de {}", event.messageId(), event.tenant(), ex);
            }
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    public void handle(InboundMessageEvent e) {
        UUID tenant = e.tenant();
        if (!ChatIds.isPhoneChat(e.chat()) || !repo.claim(tenant, e.chat(), e.messageId())) return;
        String skip = skipReason(tenant, e.chat());
        if (skip != null) {
            repo.finish(tenant, e.chat(), e.messageId(), "SKIPPED", skip, null, null, null, null);
            return;
        }
        long started = System.nanoTime();
        if (HUMAN.matcher(normalize(e.body())).find()) {
            inbox.escalateByAssistant(tenant, e.chat(), "Pidió hablar con una persona", "Último mensaje: " + e.body());
            send(e, HANDOFF, "ESCALATED", "", started);
            return;
        }
        Answer answer;
        try {
            var facts = repo.facts(tenant, baseUrl).orElseThrow();
            var full = conversations.find(tenant, e.chat()).orElseThrow();
            answer = responder.answer(tenant, facts, repo.settings(tenant).instructions(), history(tenant, e.chat()),
                    full.handbackNote(), e.body());
        } catch (RuntimeException ex) {
            log.error("IA vendedora: el modelo falló en {}: {}", tenant, GeminiResponder.describe(ex));
            inbox.escalateByAssistant(tenant, e.chat(), "La IA no pudo responder", "Último mensaje: " + e.body());
            repo.finish(tenant, e.chat(), e.messageId(), "FAILED", null, null, null, elapsed(started), GeminiResponder.describe(ex));
            return;
        }
        if (answer.escalate())
            inbox.escalateByAssistant(tenant, e.chat(), answer.reason() == null || answer.reason().isBlank() ? "La IA pidió ayuda" : answer.reason(),
                    answer.summary());
        send(e, answer.text(), answer.escalate() ? "ESCALATED" : "SENT", String.join(",", answer.toolsUsed()), started);
    }

    private void send(InboundMessageEvent e, String text, String okStatus, String tools, long started) {
        UUID request = UUID.nameUUIDFromBytes(("ai/" + e.tenant() + "/" + e.chat() + "/" + e.messageId()).getBytes(StandardCharsets.UTF_8));
        String result;
        try {
            result = replies.replyAsAssistant(e.tenant(), request, e.chat(), text);
        } catch (RuntimeException ex) {
            result = "REJECTED";
        }
        boolean delivered = !"REJECTED".equals(result);
        repo.finish(e.tenant(), e.chat(), e.messageId(), delivered ? okStatus : "FAILED", null, text, tools, elapsed(started),
                delivered ? null : "El proveedor de WhatsApp rechazó el envío");
    }

    /** Motivo para no responder, o null si la IA puede contestar. */
    private String skipReason(UUID tenant, String chat) {
        if (!repo.platformEnabled()) return "platform_off";
        if (!repo.settings(tenant).enabled()) return "store_off";
        if (!responder.configured()) return "not_configured";
        var conversation = conversations.find(tenant, chat);
        if (conversation.isEmpty()) return "no_conversation";
        if (!conversation.get().row().aiEnabled()) return "conversation_off";
        if (conversation.get().row().status() != com.saas.omnicanal.inbox.model.InboxViews.Status.OPEN) return "escalated";
        var quota = entitlements.get(tenant, QUOTA);
        if (!quota.enabled()) return "plan";
        if (quota.limit() != null && repo.sentThisMonth(tenant) >= quota.limit()) return "quota";
        return null;
    }

    private List<Turn> history(UUID tenant, String chat) {
        List<Message> all = conversations.messages(tenant, chat, 13);
        // El último es el mensaje actual del cliente: va aparte como "user".
        var previous = all.isEmpty() ? all : all.subList(0, all.size() - 1);
        return previous.stream().map(m -> new Turn(m.author(), m.body())).toList();
    }

    private static Integer elapsed(long started) {
        return (int) ((System.nanoTime() - started) / 1_000_000);
    }

    static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text.toLowerCase(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
