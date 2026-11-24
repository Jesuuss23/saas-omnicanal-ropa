package com.saas.omnicanal.inbox.service;

import com.saas.omnicanal.inbox.model.InboxViews.Conversation;
import com.saas.omnicanal.inbox.model.InboxViews.Counts;
import com.saas.omnicanal.inbox.model.InboxViews.Row;
import com.saas.omnicanal.inbox.model.InboxViews.Status;
import com.saas.omnicanal.inbox.repository.ConversationRepository;
import com.saas.omnicanal.notifications.service.EmailService;
import com.saas.omnicanal.notifications.service.EmailTemplates;
import com.saas.omnicanal.whatsapp.model.InboundMessageEvent;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Estados de la conversación: OPEN (la IA puede responder), ESCALATED (una persona debe responder; la IA calla)
 * y CLOSED (se reabre sola con el siguiente mensaje del cliente).
 */
@Service
public class InboxService {
    private static final Logger log = LoggerFactory.getLogger(InboxService.class);

    private final ConversationRepository conversations;
    private final EmailService email;
    private final EmailTemplates templates;
    private final String baseUrl;

    public InboxService(ConversationRepository conversations, EmailService email, EmailTemplates templates,
                        @Value("${app.base-url:http://localhost:3000}") String baseUrl) {
        this.conversations = conversations;
        this.email = email;
        this.templates = templates;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    /** Corre en la misma transacción que guarda el mensaje del webhook. */
    @EventListener
    public void onInbound(InboundMessageEvent event) {
        conversations.inbound(event.tenant(), event.chat());
    }

    public void outbound(UUID tenant, String chat) {
        conversations.outbound(tenant, chat);
    }

    @Transactional(readOnly = true)
    public List<Row> list(UUID tenant, Status status, String query) {
        if (query.length() > 20) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return conversations.list(tenant, status, query.replaceAll("[^0-9]", ""), 100);
    }

    @Transactional(readOnly = true)
    public Counts counts(UUID tenant) {
        return conversations.counts(tenant);
    }

    @Transactional(readOnly = true)
    public Conversation conversation(UUID tenant, String chat) {
        var full = conversations.find(tenant, chat).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new Conversation(full.row(), full.reason(), full.summary(), full.handbackNote(), full.revision(),
                conversations.messages(tenant, chat, 60));
    }

    public boolean aiMayReply(UUID tenant, String chat) {
        return conversations.find(tenant, chat).map(c -> c.row().aiEnabled() && c.row().status() == Status.OPEN).orElse(false);
    }

    /** Una persona del equipo toma la conversación: la IA deja de responder hasta que la devuelva. */
    @Transactional
    public Conversation take(UUID tenant, String chat, String by) {
        lock(tenant, chat);
        conversations.escalate(tenant, chat, "Atendida por " + by, null, by, true);
        return conversation(tenant, chat);
    }

    /** Devuelve la conversación a la IA con una nota que la IA leerá como contexto. */
    @Transactional
    public Conversation handback(UUID tenant, String chat, String note) {
        lock(tenant, chat);
        conversations.handback(tenant, chat, note == null || note.isBlank() ? null : note.strip());
        return conversation(tenant, chat);
    }

    @Transactional
    public Conversation setStatus(UUID tenant, String chat, Status status) {
        lock(tenant, chat);
        if (status == Status.ESCALATED) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        conversations.setStatus(tenant, chat, status);
        return conversation(tenant, chat);
    }

    @Transactional
    public Conversation setAi(UUID tenant, String chat, boolean enabled) {
        lock(tenant, chat);
        conversations.setAi(tenant, chat, enabled);
        return conversation(tenant, chat);
    }

    /** Escalamiento pedido por la IA (o por palabras clave). Avisa al dueño por correo una vez por escalada. */
    @Transactional
    public void escalateByAssistant(UUID tenant, String chat, String reason, String summary) {
        var current = lock(tenant, chat);
        conversations.escalate(tenant, chat, reason, summary, null, false);
        if (!conversations.markAlerted(tenant, chat)) return;
        String customer = current.row().contactName() != null ? current.row().contactName()
                : current.row().phone() != null ? "+" + current.row().phone() : "Un cliente";
        conversations.owner(tenant).ifPresent(o -> {
            try {
                email.send(templates.conversationEscalated(o.email(), o.storeName(), customer, reason,
                        baseUrl + "/propietario/" + tenant + "/whatsapp?chat=" + chat));
            } catch (RuntimeException ex) {
                log.warn("No se pudo enviar la alerta de escalamiento de {}: {}", tenant, ex.getMessage());
            }
        });
    }

    private ConversationRepository.Full lock(UUID tenant, String chat) {
        return conversations.lock(tenant, chat).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
