package com.saas.omnicanal.whatsapp.service;

import com.saas.omnicanal.whatsapp.repository.WhatsAppChannelSettingRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/** Conexión rápida por QR con Evolution API: vincular instancia y activar el webhook del inbox. */
@Service
public class EvolutionConnectionService {
    public record Connection(String state, String qr) {}

    private final EvolutionApiClient evolution;
    private final WhatsAppChannelSettingRepository channels;
    private final EvolutionWebhookService webhooks;
    private final String callbackBase;

    public EvolutionConnectionService(EvolutionApiClient evolution, WhatsAppChannelSettingRepository channels,
                                      EvolutionWebhookService webhooks,
                                      @Value("${EVOLUTION_CALLBACK_BASE_URL:${PUBLIC_API_BASE_URL:}}") String callbackBase) {
        this.evolution = evolution;
        this.channels = channels;
        this.webhooks = webhooks;
        this.callbackBase = callbackBase.replaceAll("/+$", "");
    }

    private String instance(UUID store) {
        if (!evolution.configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return EvolutionApiClient.instanceName(store);
    }

    public Connection status(UUID store) {
        String name = instance(store);
        try {
            return new Connection(evolution.state(name), null);
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        }
    }

    public Connection connect(UUID store) {
        String name = instance(store);
        if (!channels.bindEvolutionInstance(store, name)) throw new ResponseStatusException(HttpStatus.CONFLICT);
        try {
            String current = evolution.state(name);
            if ("CONNECTED".equals(current)) return new Connection(current, null);
            if ("UNLINKED".equals(current)) {
                try {
                    String qr = evolution.createInstance(name);
                    if (qr != null) return new Connection("PAIRING", qr);
                } catch (RestClientResponseException conflict) {
                    // Another request may have created our deterministic instance.
                    if ("UNLINKED".equals(evolution.state(name))) throw conflict;
                }
            }
            String qr = evolution.connect(name);
            return new Connection(qr == null ? evolution.state(name) : "PAIRING", qr);
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        }
    }

    public void enableInbox(UUID store) {
        String name = instance(store);
        if (callbackBase.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        if (!channels.evolutionInstanceBound(store, name)) throw new ResponseStatusException(HttpStatus.CONFLICT);
        try {
            evolution.setWebhook(name, callbackBase + EvolutionWebhookService.ROOT + "/" + store,
                    Map.of(EvolutionWebhookService.HEADER, webhooks.credential(store)));
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        }
    }
}
