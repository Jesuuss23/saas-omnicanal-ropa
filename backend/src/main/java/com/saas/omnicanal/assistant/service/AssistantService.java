package com.saas.omnicanal.assistant.service;

import com.saas.omnicanal.assistant.model.AssistantModels.Answer;
import com.saas.omnicanal.assistant.model.AssistantModels.Settings;
import com.saas.omnicanal.assistant.model.AssistantModels.Status;
import com.saas.omnicanal.assistant.model.AssistantModels.StoreFacts;
import com.saas.omnicanal.assistant.model.AssistantModels.Turn;
import com.saas.omnicanal.assistant.repository.AssistantRepository;
import com.saas.omnicanal.inbox.model.InboxViews.Message;
import com.saas.omnicanal.inbox.repository.ConversationRepository;
import com.saas.omnicanal.inbox.service.InboxService;
import com.saas.omnicanal.shared.web.PublicLinks;
import com.saas.omnicanal.subscription.service.EntitlementService;
import com.saas.omnicanal.whatsapp.model.ChatIds;
import com.saas.omnicanal.whatsapp.model.InboundMessageEvent;
import com.saas.omnicanal.whatsapp.model.MediaKind;
import com.saas.omnicanal.assistant.model.AssistantModels;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;

/**
 * IA vendedora. Cada mensaje entrante pasa por tres interruptores (plataforma, tienda y conversación), la cuota
 * mensual del plan y el estado de la conversación antes de llegar al modelo. Todo queda en {@code ai_reply}.
 *
 * <p>Mensajes seguidos del mismo chat se agrupan: cada uno queda PENDING y reinicia una espera de
 * {@code assistant.debounce-ms}; al vencer, todos los pendientes van juntos al modelo y reciben una sola respuesta.
 *
 * <p>Solo atiende temas de la tienda. Una pregunta ajena recibe un aviso fijo; la segunda seguida pausa la IA en la
 * conversación (sin escalar), y desde ahí los mensajes se ignoran antes de llamar al modelo hasta que el equipo
 * intervenga (activar IA, tomar o devolver la conversación) o la conversación se cierre y el cliente vuelva a escribir.
 */
@Service
public class AssistantService {
    public static final String QUOTA = "ai.responses.monthly";
    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);
    /** Pedidos explícitos de una persona: se escala sin pasar por el modelo. */
    private static final Pattern HUMAN = Pattern.compile(
            "\\b(humano|una persona|asesor|asesora|encargad[oa]|hablar con alguien|reclamo|queja|libro de reclamaciones)\\b");
    private static final String HANDOFF = "Gracias por escribirnos. Te comunico con una persona del equipo, que te responderá pronto.";
    /** Preguntas fuera de tema seguidas que pausan la IA en la conversación. */
    public static final int OFF_TOPIC_LIMIT = 2;
    static final String OFF_TOPIC_PAUSE = "Como tus consultas no son sobre la tienda, pausaré el asistente automático en esta "
            + "conversación. ¡Gracias por escribirnos!";

    private final AssistantRepository repo;
    private final ConversationRepository conversations;
    private final InboxService inbox;
    private final EntitlementService entitlements;
    private final AssistantResponder responder;
    private final WhatsAppReplyService replies;
    private final String baseUrl;
    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    private final Debouncer batches;

    public AssistantService(AssistantRepository repo, ConversationRepository conversations, InboxService inbox,
                            EntitlementService entitlements, AssistantResponder responder, WhatsAppReplyService replies,
                            @Value("${app.base-url:http://localhost:3000}") String baseUrl,
                            @Value("${assistant.debounce-ms:8000}") long debounceMs) {
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
        batches = new Debouncer(executor, debounceMs);
    }

    static String offTopicWarning(String store) {
        return "Solo puedo ayudarte con consultas sobre " + store + ": prendas, precios, tallas, stock, envíos y pedidos. "
                + "¿Qué estás buscando?";
    }

    // --- Panel ---

    public Settings settings(UUID tenant) {
        return repo.settings(tenant);
    }

    public Settings save(UUID tenant, long revision, boolean enabled, String instructions, int sla) {
        return save(tenant, revision, enabled, instructions, sla, true);
    }

    public Settings save(UUID tenant, long revision, boolean enabled, String instructions, int sla, boolean nsfwPauseAi) {
        if (!repo.saveSettings(tenant, revision, enabled, instructions.strip(), sla, nsfwPauseAi))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Otra persona cambió la configuración. Recarga la página.");
        return repo.settings(tenant);
    }

    public Status status(UUID tenant) {
        var quota = entitlements.get(tenant, QUOTA);
        return new Status(responder.configured(), repo.platformEnabled(), quota.enabled(), repo.sentThisMonth(tenant), quota.limit(),
                batches.delayMs());
    }

    /**
     * Prueba desde el panel: responde sin enviar nada al cliente ni contar para la cuota. Aplica las mismas reglas que
     * WhatsApp; el panel guarda {@code offTopicCount} (avisos seguidos fuera de tema) y lo manda en cada prueba.
     */
    public Answer test(UUID tenant, List<Turn> history, String message, int offTopicCount) {
        if (!responder.configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "La IA no está configurada en el servidor.");
        var facts = repo.facts(tenant, baseUrl).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // Pausada: igual que en WhatsApp, se ignora sin llamar al modelo.
        if (offTopicCount >= OFF_TOPIC_LIMIT) return new Answer("", false, null, null, List.of(), false, true);
        if (HUMAN.matcher(normalize(message)).find())
            return new Answer(HANDOFF, true, "Pidió hablar con una persona", message, List.of());
        Answer answer;
        try {
            answer = responder.answer(tenant, facts, repo.settings(tenant).instructions(), history, null, message);
        } catch (RuntimeException ex) {
            log.error("Prueba de IA falló en {}: {}", tenant, GeminiResponder.describe(ex));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "El modelo no respondió. Intenta de nuevo en un momento.");
        }
        if (!answer.offTopic())
            return new Answer(PublicLinks.stripPrivate(answer.text()), answer.escalate(), answer.reason(), answer.summary(),
                    answer.toolsUsed(), false, false, answer.attachments());
        boolean pause = offTopicCount + 1 >= OFF_TOPIC_LIMIT;
        return new Answer(pause ? OFF_TOPIC_PAUSE : offTopicWarning(facts.storeName()), false, null, null, answer.toolsUsed(), true, pause);
    }

    // --- Mensajes entrantes ---

    /**
     * Corre después de confirmar el mensaje (fuera de la transacción del webhook): reserva el mensaje y reinicia la
     * espera del chat. El proveedor recibe su 200 de inmediato; el modelo corre en un ejecutor propio al vencer la espera.
     */
    @TransactionalEventListener(fallbackExecution = true)
    public void onInbound(InboundMessageEvent event) {
        if (!event.forAssistant()) return;
        try {
            if (receive(event)) schedule(event.tenant(), event.chat());
        } catch (RuntimeException ex) {
            log.error("IA vendedora: error recibiendo {} de {}", event.messageId(), event.tenant(), ex);
        }
    }

    @PreDestroy
    void shutdown() {
        batches.close();
        executor.shutdown();
    }

    /** Reserva el mensaje (PENDING); false si no es un chat individual (teléfono o @lid) o ya se había recibido (webhook repetido). */
    public boolean receive(InboundMessageEvent e) {
        return ChatIds.isDirectChat(e.chat()) && repo.claim(e.tenant(), e.chat(), e.messageId());
    }

    private void schedule(UUID tenant, String chat) {
        batches.submit(tenant + "|" + chat, () -> {
            try {
                flush(tenant, chat);
            } catch (RuntimeException ex) {
                log.error("IA vendedora: error respondiendo el chat {} de {}", chat, tenant, ex);
            }
        });
    }

    /** Recupera mensajes que quedaron en espera (por ejemplo, si el servidor se reinició antes de responderlos). */
    @Scheduled(fixedDelayString = "${assistant.sweep-ms:60000}", initialDelayString = "${assistant.sweep-initial-delay-ms:30000}")
    public void sweep() {
        int failed = repo.failStuckWorking(15);
        if (failed > 0) log.warn("IA vendedora: {} mensajes quedaron a medias y se marcaron FAILED", failed);
        int seconds = (int) (batches.delayMs() / 1000) + 30;
        for (var key : repo.stalePending(seconds))
            if (!batches.waiting(key.tenant() + "|" + key.chat())) schedule(key.tenant(), key.chat());
    }

    static final String NSFW_WARNING = "Recibimos una imagen que no podemos mostrar ni revisar porque parece contenido explícito. "
            + "Por favor envía solo fotos de las prendas que te interesan o escríbenos qué buscas.";

    /**
     * Imagen bloqueada por contenido explícito: se avisa al cliente por el canal activo (sin llamar al modelo ni contar
     * para el plan) y, si la tienda lo tiene configurado, se pausa la IA en la conversación. Una sola vez por mensaje.
     */
    public void blockedImage(UUID tenant, String chat, String messageId) {
        if (!repo.claim(tenant, chat, messageId)) return;
        boolean pause = repo.settings(tenant).nsfwPauseAi();
        if (pause) conversations.pauseForNsfw(tenant, chat);
        UUID request = UUID.nameUUIDFromBytes(("nsfw/" + tenant + "/" + chat + "/" + messageId).getBytes(StandardCharsets.UTF_8));
        String result;
        try {
            result = replies.replyAsAssistant(tenant, request, chat, NSFW_WARNING);
        } catch (RuntimeException ex) {
            log.warn("IA vendedora: no se pudo enviar el aviso de contenido explícito a {} de {}: {}", chat, tenant, ex.getMessage());
            result = "REJECTED";
        }
        repo.finish(tenant, chat, messageId, "SKIPPED", "nsfw_blocked", NSFW_WARNING, "", null,
                "REJECTED".equals(result) ? "No se pudo enviar el aviso" : null);
    }

    /**
     * Foto ajena a la tienda (según el análisis visual): aplica la misma regla que una pregunta fuera de tema
     * (aviso y, a la segunda seguida, pausa sin derivar a una persona), sin llamar al modelo conversacional.
     */
    public void offContextImage(UUID tenant, String chat, String messageId) {
        if (!repo.claim(tenant, chat, messageId)) return;
        String skip = skipReason(tenant, chat);
        if (skip != null) {
            repo.finish(tenant, chat, messageId, "SKIPPED", skip, null, null, null, null);
            return;
        }
        long started = System.nanoTime();
        var e = new InboundMessageEvent(tenant, chat, messageId, "[Imagen]");
        var facts = repo.facts(tenant, baseUrl).orElseThrow();
        if (conversations.offTopic(tenant, chat) >= OFF_TOPIC_LIMIT) {
            conversations.pauseForOffTopic(tenant, chat);
            send(e, OFF_TOPIC_PAUSE, "SENT", "vision", started);
        } else {
            send(e, offTopicWarning(facts.storeName()), "SENT", "vision", started);
        }
    }

    /** Recibe y responde en el acto, sin esperar la ventana de agrupación. */
    public void handle(InboundMessageEvent e) {
        if (receive(e)) flush(e.tenant(), e.chat());
    }

    /** Toma todos los mensajes en espera del chat y los responde con una sola llamada al modelo. */
    public void flush(UUID tenant, String chat) {
        var batch = repo.takeBatch(tenant, chat);
        if (batch.isEmpty()) return;
        var lead = batch.get(batch.size() - 1);
        var others = batch.subList(0, batch.size() - 1).stream().map(AssistantRepository.Pending::messageId).toList();
        repo.markBatched(tenant, chat, others, lead.messageId());
        String body = String.join("\n", batch.stream().map(AssistantRepository.Pending::body).filter(b -> !b.isBlank()).toList());
        answer(new InboundMessageEvent(tenant, chat, lead.messageId(), body), batch.size());
    }

    private void answer(InboundMessageEvent e, int batchSize) {
        UUID tenant = e.tenant();
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
        StoreFacts facts;
        try {
            facts = repo.facts(tenant, baseUrl).orElseThrow();
            var full = conversations.find(tenant, e.chat()).orElseThrow();
            answer = responder.answer(tenant, facts, repo.settings(tenant).instructions(), history(tenant, e.chat(), batchSize),
                    full.handbackNote(), e.body());
        } catch (RuntimeException ex) {
            log.error("IA vendedora: el modelo falló en {}: {}", tenant, GeminiResponder.describe(ex));
            inbox.escalateByAssistant(tenant, e.chat(), "La IA no pudo responder", "Último mensaje: " + e.body());
            repo.finish(tenant, e.chat(), e.messageId(), "FAILED", null, null, null, elapsed(started), GeminiResponder.describe(ex));
            return;
        }
        String tools = String.join(",", answer.toolsUsed());
        if (answer.offTopic()) {
            // Nunca se escala ni se reenvía el texto del modelo: el aviso es fijo y corto.
            if (conversations.offTopic(tenant, e.chat()) >= OFF_TOPIC_LIMIT) {
                conversations.pauseForOffTopic(tenant, e.chat());
                send(e, OFF_TOPIC_PAUSE, "SENT", tools, started);
            } else {
                send(e, offTopicWarning(facts.storeName()), "SENT", tools, started);
            }
            return;
        }
        conversations.resetOffTopic(tenant, e.chat());
        if (answer.escalate())
            inbox.escalateByAssistant(tenant, e.chat(), answer.reason() == null || answer.reason().isBlank() ? "La IA pidió ayuda" : answer.reason(),
                    answer.summary());
        send(e, answer.text(), answer.escalate() ? "ESCALATED" : "SENT", tools, started);
        sendAttachments(e, answer.attachments());
    }

    /** Catálogo de la categoría (PDF o imagen) después del texto. Si el envío falla, el texto ya salió y se registra. */
    private void sendAttachments(InboundMessageEvent e, List<AssistantModels.Attachment> attachments) {
        for (int i = 0; i < attachments.size(); i++) {
            var a = attachments.get(i);
            UUID request = UUID.nameUUIDFromBytes(("ai-media/" + e.tenant() + "/" + e.chat() + "/" + e.messageId() + "/" + i)
                    .getBytes(StandardCharsets.UTF_8));
            try {
                replies.replyMediaAsAssistant(e.tenant(), request, e.chat(), new WhatsAppReplyService.Outgoing(
                        MediaKind.valueOf(a.kind()), a.mimeType(), a.fileName(), a.content(), null));
            } catch (RuntimeException ex) {
                log.warn("IA vendedora: no se pudo enviar el catálogo a {} de {}: {}", e.chat(), e.tenant(), ex.getMessage());
            }
        }
    }

    private void send(InboundMessageEvent e, String rawText, String okStatus, String tools, long started) {
        // Red de seguridad: aunque el modelo invente un enlace de desarrollo, no llega al cliente.
        String text = PublicLinks.stripPrivate(rawText);
        if (text.isBlank()) text = "Gracias por escribirnos. ¿Me cuentas qué prenda buscas?";
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
        if (!conversation.get().row().aiEnabled())
            return "OFF_TOPIC".equals(conversation.get().row().aiPausedReason()) ? "off_topic_paused"
                    : "NSFW".equals(conversation.get().row().aiPausedReason()) ? "nsfw_paused" : "conversation_off";
        if (conversation.get().row().status() != com.saas.omnicanal.inbox.model.InboxViews.Status.OPEN) return "escalated";
        var quota = entitlements.get(tenant, QUOTA);
        if (!quota.enabled()) return "plan";
        if (quota.limit() != null && repo.sentThisMonth(tenant) >= quota.limit()) return "quota";
        return null;
    }

    /** Últimos 12 mensajes antes del lote actual (los {@code batchSize} mensajes del cliente van aparte como "user"). */
    private List<Turn> history(UUID tenant, String chat, int batchSize) {
        List<Message> all = new java.util.ArrayList<>(conversations.messages(tenant, chat, 12 + batchSize));
        for (int dropped = 0; dropped < batchSize && !all.isEmpty() && "CUSTOMER".equals(all.get(all.size() - 1).author()); dropped++)
            all.remove(all.size() - 1);
        if (all.size() > 12) all = all.subList(all.size() - 12, all.size());
        return all.stream().map(m -> new Turn(m.author(), m.aiContext() != null ? m.aiContext() : m.body())).toList();
    }

    private static Integer elapsed(long started) {
        return (int) ((System.nanoTime() - started) / 1_000_000);
    }

    static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text.toLowerCase(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
