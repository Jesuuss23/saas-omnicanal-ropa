package com.saas.omnicanal.whatsapp.service;

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

    public WhatsAppReplyService(WhatsAppMessageRepository messages, WhatsAppChannelSettingRepository channels,
                                EvolutionApiClient evolution, CloudApiService cloud, CloudApiClient cloudClient) {
        this.messages = messages;
        this.channels = channels;
        this.evolution = evolution;
        this.cloud = cloud;
        this.cloudClient = cloudClient;
    }

    public List<ManualReply> replies(UUID store, String chat) {
        return messages.recentReplies(store, chat, 5);
    }

    public String reply(UUID store, UUID requestId, String chat, String rawText) {
        String text = rawText.strip();
        if (!ChatIds.isPhoneChat(chat)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        WhatsAppProvider provider = channels.activeProvider(store);
        if (provider == WhatsAppProvider.EVOLUTION) {
            if (!evolution.configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
            if (!channels.evolutionInstanceBound(store, EvolutionApiClient.instanceName(store)))
                throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        if (!messages.claimReply(store, requestId, chat, text, provider)) {
            return messages.existingReplyStatus(store, requestId, chat, text)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT));
        }
        SendResult result = switch (provider) {
            case EVOLUTION -> evolution.sendText(EvolutionApiClient.instanceName(store), ChatIds.phoneOf(chat), text);
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
        return status;
    }
}
