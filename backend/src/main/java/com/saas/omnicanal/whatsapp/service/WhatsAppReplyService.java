package com.saas.omnicanal.whatsapp.service;

import com.saas.omnicanal.inbox.service.InboxService;
import com.saas.omnicanal.whatsapp.model.ChatIds;
import com.saas.omnicanal.whatsapp.model.ManualReply;
import com.saas.omnicanal.whatsapp.model.SendResult;
import com.saas.omnicanal.whatsapp.model.WhatsAppProvider;
import com.saas.omnicanal.whatsapp.repository.WhatsAppChannelSettingRepository;
import com.saas.omnicanal.whatsapp.repository.WhatsAppMessageRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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

    public WhatsAppReplyService(WhatsAppMessageRepository messages, WhatsAppChannelSettingRepository channels,
                                EvolutionApiClient evolution, CloudApiService cloud, CloudApiClient cloudClient, InboxService inbox) {
        this.inbox = inbox;
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

    private String send(UUID store, UUID requestId, String chat, String rawText, String author) {
        String text = rawText.strip();
        if (!ChatIds.isDirectChat(chat)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        WhatsAppProvider provider = channels.activeProvider(store);
        // Cloud API solo envía a números; un chat @lid solo existe en Evolution.
        if (provider == WhatsAppProvider.CLOUD_API && !ChatIds.isPhoneChat(chat))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este chat solo se puede responder por la conexión con QR (Evolution).");
        if (provider == WhatsAppProvider.EVOLUTION) {
            if (!evolution.configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
            if (!channels.evolutionInstanceBound(store, EvolutionApiClient.instanceName(store)))
                throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        if (!messages.claimReply(store, requestId, chat, text, provider, author)) {
            return messages.existingReplyStatus(store, requestId, chat, text)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT));
        }
        SendResult result = switch (provider) {
            // Evolution 2.3.x acepta el remoteJid completo (@lid o @s.whatsapp.net) en "number": se envía tal cual.
            case EVOLUTION -> evolution.sendText(EvolutionApiClient.instanceName(store), chat, text);
            case CLOUD_API -> cloud.credentials(store)
                    .map(c -> cloudClient.sendText(c, ChatIds.phoneOf(chat), text))
                    .orElse(SendResult.rejected("Cloud API sin configurar"));
        };
        String status = switch (result.status()) {
            case "ACCEPTED" -> "ACCEPTED";
            case "REJECTED" -> "REJECTED";
            default -> "REVIEW";
        };
        messages.finishReply(store, requestId, status, result.providerMessageId());
        if (!"REJECTED".equals(status)) inbox.outbound(store, chat);
        return status;
    }
}
