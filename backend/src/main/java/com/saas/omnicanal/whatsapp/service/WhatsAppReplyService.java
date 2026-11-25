package com.saas.omnicanal.whatsapp.service;

import com.saas.omnicanal.inbox.service.InboxService;
import com.saas.omnicanal.subscription.model.Entitlements;
import com.saas.omnicanal.subscription.service.EntitlementService;
import com.saas.omnicanal.whatsapp.model.ChatIds;
import com.saas.omnicanal.whatsapp.model.MediaKind;
import com.saas.omnicanal.whatsapp.repository.WhatsAppMediaRepository;
import com.saas.omnicanal.whatsapp.model.ManualReply;
import com.saas.omnicanal.whatsapp.model.SendResult;
import com.saas.omnicanal.whatsapp.model.WhatsAppProvider;
import com.saas.omnicanal.whatsapp.repository.WhatsAppChannelSettingRepository;
import com.saas.omnicanal.whatsapp.repository.WhatsAppMessageRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/** Respuestas manuales del inbox: se envían por el proveedor activo de la tienda (Evolution o Cloud API). */
@Service
public class WhatsAppReplyService {
    private final WhatsAppMessageRepository messages;
    private final WhatsAppChannelSettingRepository channels;
    private final EvolutionApiClient evolution;
    private final CloudApiService cloud;
    private final CloudApiClient cloudClient;
    private final InboxService inbox;
    private final WhatsAppMediaRepository media;
    private final EntitlementService entitlements;

    public WhatsAppReplyService(WhatsAppMessageRepository messages, WhatsAppChannelSettingRepository channels,
                                EvolutionApiClient evolution, CloudApiService cloud, CloudApiClient cloudClient, InboxService inbox,
                                WhatsAppMediaRepository media, EntitlementService entitlements) {
        this.inbox = inbox;
        this.media = media;
        this.entitlements = entitlements;
        this.messages = messages;
        this.channels = channels;
        this.evolution = evolution;
        this.cloud = cloud;
        this.cloudClient = cloudClient;
    }

    public List<ManualReply> replies(UUID store, String chat) {
        return messages.recentReplies(store, chat, 5);
    }

    /** Respuesta escrita por una persona del equipo desde el inbox. */
    public String reply(UUID store, UUID requestId, String chat, String rawText) {
        return send(store, requestId, chat, rawText, "HUMAN");
    }

    /** Respuesta de la IA vendedora; el request id se deriva del mensaje entrante, así no se envía dos veces. */
    public String replyAsAssistant(UUID store, UUID requestId, String chat, String text) {
        return send(store, requestId, chat, text, "AI");
    }

    /** Si el plan de la tienda permite enviar imágenes y videos. */
    public boolean canSendMedia(UUID store) {
        return entitlements.enabled(store, Entitlements.WHATSAPP_MEDIA_SEND);
    }

    public record Outgoing(MediaKind kind, String mimeType, String fileName, byte[] content, String caption) {}

    /** Archivo enviado desde la bandeja por una persona del equipo. */
    public String replyMedia(UUID store, UUID requestId, String chat, Outgoing file) {
        return sendMedia(store, requestId, chat, file, "HUMAN");
    }

    /** Archivo enviado por la IA (catálogo PDF o imagen de la categoría). */
    public String replyMediaAsAssistant(UUID store, UUID requestId, String chat, Outgoing file) {
        return sendMedia(store, requestId, chat, file, "AI");
    }

    private String sendMedia(UUID store, UUID requestId, String chat, Outgoing file, String author) {
        if (file.kind() == MediaKind.AUDIO) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Solo se envían imágenes, videos o PDF.");
        if (file.kind().needsPlan() && !canSendMedia(store))
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "Tu plan no incluye enviar imágenes ni videos por WhatsApp. Está disponible en el plan Premium.");
        if (file.content().length > WhatsAppMediaRepository.MAX_BYTES)
            throw new ResponseStatusException(HttpStatus.valueOf(413), "El archivo supera 16 MB.");
        WhatsAppProvider provider = provider(store, chat);
        String caption = file.caption() == null ? "" : file.caption().strip();
        String body = caption.isEmpty() ? file.kind().placeholder() : caption;
        UUID id = media.store(store, file.kind(), file.mimeType(), file.fileName(), file.content());
        if (!messages.claimMediaReply(store, requestId, chat, body, provider, author, file.kind(), file.mimeType(),
                WhatsAppMediaRepository.url(id), caption.isEmpty() ? null : caption)) {
            media.delete(store, id);
            return messages.existingReplyStatus(store, requestId, chat, body)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, NO_CHAT));
        }
        String type = switch (file.kind()) {
            case IMAGE -> "image";
            case VIDEO -> "video";
            default -> "document";
        };
        SendResult result = switch (provider) {
            case EVOLUTION -> evolution.sendMedia(EvolutionApiClient.instanceName(store), chat, type, file.mimeType(), file.fileName(),
                    file.content(), caption);
            case CLOUD_API -> cloud.credentials(store)
                    .map(c -> cloudClient.sendMedia(c, ChatIds.phoneOf(chat), type, file.mimeType(), file.fileName(), file.content(), caption))
                    .orElse(SendResult.rejected("Cloud API sin configurar"));
        };
        return finish(store, requestId, chat, result);
    }

    /** Proveedor activo, validando que pueda responder este chat. */
    private WhatsAppProvider provider(UUID store, String chat) {
        if (!ChatIds.isDirectChat(chat)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        WhatsAppProvider provider = channels.activeProvider(store);
        if (provider == WhatsAppProvider.CLOUD_API && !ChatIds.isPhoneChat(chat))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este chat solo se puede responder por la conexión con QR (Evolution).");
        if (provider == WhatsAppProvider.EVOLUTION) {
            if (!evolution.configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Falta configurar Evolution API en el servidor.");
            String name = EvolutionApiClient.instanceName(store);
            if (!channels.evolutionInstanceBound(store, name)) requireLiveConnection(store, name);
        }
        return provider;
    }

    static final String NOT_CONNECTED = "Conecta el número de WhatsApp antes de responder.";
    static final String NO_CHAT = "Este chat todavía no tiene mensajes del cliente; no se puede responder.";

    /**
     * Antes de rechazar con 409, pregunta en vivo a Evolution ({@code GET /instance/connectionState/{instance}}). Si está
     * abierta (por ejemplo, se vinculó desde Evolution Manager y no desde el panel), guarda el estado, asigna la instancia
     * y deja seguir el envío.
     */
    private void requireLiveConnection(UUID store, String name) {
        String state;
        try {
            state = evolution.state(name);
        } catch (RestClientException | ResponseStatusException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo consultar Evolution API. Intenta nuevamente.");
        }
        channels.saveEvolutionState(store, state);
        if (!"CONNECTED".equals(state) || !channels.bindEvolutionInstance(store, name))
            throw new ResponseStatusException(HttpStatus.CONFLICT, NOT_CONNECTED);
    }

    private String finish(UUID store, UUID requestId, String chat, SendResult result) {
        String status = switch (result.status()) {
            case "ACCEPTED" -> "ACCEPTED";
            case "REJECTED" -> "REJECTED";
            default -> "REVIEW";
        };
        messages.finishReply(store, requestId, status, result.providerMessageId());
        if (!"REJECTED".equals(status)) inbox.outbound(store, chat);
        return status;
    }

    private String send(UUID store, UUID requestId, String chat, String rawText, String author) {
        String text = rawText.strip();
        WhatsAppProvider provider = provider(store, chat);
        if (!messages.claimReply(store, requestId, chat, text, provider, author)) {
            return messages.existingReplyStatus(store, requestId, chat, text)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, NO_CHAT));
        }
        SendResult result = switch (provider) {
            // Evolution 2.3.x acepta el remoteJid completo (@lid o @s.whatsapp.net) en "number": se envía tal cual.
            case EVOLUTION -> evolution.sendText(EvolutionApiClient.instanceName(store), chat, text);
            case CLOUD_API -> cloud.credentials(store)
                    .map(c -> cloudClient.sendText(c, ChatIds.phoneOf(chat), text))
                    .orElse(SendResult.rejected("Cloud API sin configurar"));
        };
        return finish(store, requestId, chat, result);
    }
}
