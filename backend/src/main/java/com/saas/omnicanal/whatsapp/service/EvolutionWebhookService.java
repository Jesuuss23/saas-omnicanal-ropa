package com.saas.omnicanal.whatsapp.service;

import com.saas.omnicanal.inbox.service.InboxService;
import com.saas.omnicanal.shared.crypto.Hmac;
import com.saas.omnicanal.whatsapp.model.ChatIds;
import com.saas.omnicanal.whatsapp.model.WhatsAppProvider;
import com.saas.omnicanal.whatsapp.repository.WhatsAppChannelSettingRepository;
import com.saas.omnicanal.whatsapp.repository.WhatsAppMessageRepository;
import com.saas.omnicanal.whatsapp.model.InboundMessageEvent;
import com.saas.omnicanal.whatsapp.model.InboundMediaEvent;
import com.saas.omnicanal.whatsapp.model.MediaKind;
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
    private final InboxService inbox;

    public EvolutionWebhookService(@Value("${EVOLUTION_WEBHOOK_SECRET:}") String secret,
                                   WhatsAppChannelSettingRepository channels, WhatsAppMessageRepository messages,
                                   ApplicationEventPublisher events, InboxService inbox) {
        this.events = events;
        this.inbox = inbox;
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
        String chat = ChatIds.withoutDevice(item.path("remoteJid").asText(key.path("remoteJid").asText("")));
        if (id.isBlank() || id.length() > 200 || !ChatIds.isDirectChat(chat)) return 0;
        String rawStatus = item.path("status").asText(item.path("update").path("status").asText(""));
        String status = switch (rawStatus) {
            case "DELIVERY_ACK", "3" -> "DELIVERED";
            case "READ", "PLAYED", "4", "5" -> "READ";
            default -> null;
        };
        return status == null ? 0 : messages.saveReceipt(store, chat, id, status);
    }

    /**
     * Evolution 2.3.x entrega en MESSAGES_UPSERT tanto los mensajes del cliente como los que la tienda envía (desde el
     * celular, el panel o la IA), con {@code key.fromMe}. El chat es {@code key.remoteJid} tal cual ({@code …@lid} o
     * {@code …@s.whatsapp.net}); si es @lid, el número visible llega en {@code key.remoteJidAlt}.
     */
    private int save(UUID store, JsonNode item) {
        JsonNode key = item.path("key");
        if (!key.path("fromMe").isBoolean()) return 0;
        boolean fromMe = key.path("fromMe").asBoolean();
        String chat = ChatIds.withoutDevice(key.path("remoteJid").asText(""));
        String alt = ChatIds.withoutDevice(key.path("remoteJidAlt").asText(key.path("senderPn").asText("")));
        // Algunas versiones invierten los campos: el número en remoteJid y el @lid en remoteJidAlt. El chat sigue siendo remoteJid.
        if (!ChatIds.isDirectChat(chat)) return 0;
        String phone = ChatIds.isPhoneChat(chat) ? null : ChatIds.phoneOf(alt);
        String id = key.path("id").asText("");
        JsonNode message = unwrap(item.path("message"));
        Media media = media(message);
        String body = text(message);
        if (body.isBlank() && media != null) body = media.kind().placeholder();
        if (body.isBlank()) return 0;
        if (id.isBlank() || id.length() > 200 || chat.length() > 160) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if (fromMe) {
            // Eco de un envío del panel o de la IA, o un mensaje escrito en el celular: nunca dispara la IA.
            int saved = messages.saveFromPhone(store, chat, id, body);
            if (saved == 1) inbox.sentFromPhone(store, chat, phone);
            return saved;
        }
        int saved = media == null
                ? messages.saveInbound(store, chat, id, body, WhatsAppProvider.EVOLUTION)
                : messages.saveInboundMedia(store, chat, id, body, WhatsAppProvider.EVOLUTION, media.kind(), media.mime(), media.caption());
        if (saved == 1) {
            if (media != null) events.publishEvent(new InboundMediaEvent(store, chat, id, WhatsAppProvider.EVOLUTION,
                    media.kind(), media.mime(), item, null));
            events.publishEvent(new InboundMessageEvent(store, chat, id, body, phone));
        }
        return saved;
    }

    private record Media(MediaKind kind, String mime, String caption) {}

    /** Mensajes temporales y de "ver una vez" traen el contenido real un nivel más adentro. */
    private static JsonNode unwrap(JsonNode message) {
        for (String wrapper : List.of("ephemeralMessage", "viewOnceMessage", "viewOnceMessageV2", "documentWithCaptionMessage")) {
            JsonNode inner = message.path(wrapper).path("message");
            if (inner.isObject()) return unwrap(inner);
        }
        return message;
    }

    private static Media media(JsonNode message) {
        for (var entry : List.of(Map.entry("audioMessage", MediaKind.AUDIO), Map.entry("imageMessage", MediaKind.IMAGE),
                Map.entry("videoMessage", MediaKind.VIDEO), Map.entry("documentMessage", MediaKind.DOCUMENT))) {
            JsonNode node = message.path(entry.getKey());
            if (!node.isObject()) continue;
            String mime = node.path("mimetype").asText("");
            String caption = node.path("caption").asText("");
            return new Media(entry.getValue(), mime.isBlank() ? null : mime.split(";")[0].strip(),
                    caption.isBlank() ? null : caption);
        }
        return null;
    }

    private static String text(JsonNode message) {
        String body = message.path("conversation").asText("");
        if (body.isBlank()) body = message.path("extendedTextMessage").path("text").asText("");
        if (body.isBlank()) body = message.path("imageMessage").path("caption").asText("");
        if (body.isBlank()) body = message.path("videoMessage").path("caption").asText("");
        if (body.isBlank()) body = message.path("documentMessage").path("caption").asText("");
        return body;
    }
}
