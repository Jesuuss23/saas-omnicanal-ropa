package com.saas.omnicanal.whatsapp.service;

import com.saas.omnicanal.shared.crypto.Hmac;
import com.saas.omnicanal.whatsapp.model.WhatsAppProvider;
import com.saas.omnicanal.whatsapp.repository.WhatsAppChannelSettingRepository;
import com.saas.omnicanal.whatsapp.repository.WhatsAppMessageRepository;
import com.saas.omnicanal.whatsapp.model.InboundMessageEvent;
import org.springframework.context.ApplicationEventPublisher;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/** Procesa eventos de Evolution API (mensajes entrantes y acuses), autenticados por un header HMAC por tienda. */
@Service
public class EvolutionWebhookService {
    public static final String ROOT = "/api/webhooks/evolution";
    public static final String HEADER = "X-Store-Webhook";

    private final String secret;
    private final WhatsAppChannelSettingRepository channels;
    private final WhatsAppMessageRepository messages;
    private final ApplicationEventPublisher events;

    public EvolutionWebhookService(@Value("${EVOLUTION_WEBHOOK_SECRET:}") String secret,
                                   WhatsAppChannelSettingRepository channels, WhatsAppMessageRepository messages,
                                   ApplicationEventPublisher events) {
        this.events = events;
        this.secret = secret;
        this.channels = channels;
        this.messages = messages;
    }

    public String credential(UUID store) {
        if (secret.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                Hmac.sha256(secret.getBytes(StandardCharsets.UTF_8), store.toString().getBytes(StandardCharsets.UTF_8)));
    }

    @Transactional
    public Map<String, Integer> receive(UUID store, String key, JsonNode event) {
        if (!Hmac.equalsConstantTime(credential(store), key)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String instance = EvolutionApiClient.instanceName(store);
        if (!instance.equals(event.path("instance").asText("")) || !channels.evolutionInstanceBound(store, instance))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        String name = event.path("event").asText("").replace('_', '.');
        boolean update = "messages.update".equalsIgnoreCase(name);
        if (!update && !"messages.upsert".equalsIgnoreCase(name)) return Map.of("inserted", 0);
        JsonNode data = event.path("data");
        int count = 0;
        Iterable<JsonNode> items = data.isArray() ? data : List.of(data);
        for (JsonNode item : items) {
            count += update ? receipt(store, item) : save(store, item);
        }
        return Map.of(update ? "updated" : "inserted", count);
    }

    private int receipt(UUID store, JsonNode item) {
        JsonNode key = item.path("key");
        JsonNode outgoing = item.has("fromMe") ? item.path("fromMe") : key.path("fromMe");
        if (!outgoing.isBoolean() || !outgoing.asBoolean()) return 0;
        String id = item.path("keyId").asText(key.path("id").asText(""));
        String chat = item.path("remoteJid").asText(key.path("remoteJid").asText(""));
        chat = chat.replaceFirst(":[0-9]+@s[.]whatsapp[.]net$", "@s.whatsapp.net");
        if (id.isBlank() || id.length() > 200 || !chat.matches("[0-9]{5,15}@s[.]whatsapp[.]net")) return 0;
        String rawStatus = item.path("status").asText(item.path("update").path("status").asText(""));
        String status = switch (rawStatus) {
            case "DELIVERY_ACK", "3" -> "DELIVERED";
            case "READ", "4" -> "READ";
            default -> null;
        };
        return status == null ? 0 : messages.saveReceipt(store, chat, id, status);
    }

    private int save(UUID store, JsonNode item) {
        JsonNode key = item.path("key");
        if (!key.path("fromMe").isBoolean() || key.path("fromMe").asBoolean()) return 0;
        String chat = key.path("remoteJid").asText("");
        if (!(chat.endsWith("@s.whatsapp.net") || chat.endsWith("@lid"))) return 0;
        String id = key.path("id").asText("");
        JsonNode message = item.path("message");
        String body = message.path("conversation").asText("");
        if (body.isBlank()) body = message.path("extendedTextMessage").path("text").asText("");
        if (body.isBlank()) return 0;
        if (id.isBlank() || id.length() > 200 || chat.length() > 160) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        int saved = messages.saveInbound(store, chat, id, body, WhatsAppProvider.EVOLUTION);
        if (saved == 1) events.publishEvent(new InboundMessageEvent(store, chat, id, body));
        return saved;
    }
}
