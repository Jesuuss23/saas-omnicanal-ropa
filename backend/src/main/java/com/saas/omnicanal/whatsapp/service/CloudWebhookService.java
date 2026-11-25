package com.saas.omnicanal.whatsapp.service;

import com.saas.omnicanal.shared.crypto.Hmac;
import com.saas.omnicanal.whatsapp.model.ChatIds;
import com.saas.omnicanal.whatsapp.model.CloudApiCredentials;
import com.saas.omnicanal.whatsapp.model.WhatsAppProvider;
import com.saas.omnicanal.whatsapp.repository.WhatsAppMessageRepository;
import com.saas.omnicanal.whatsapp.model.InboundMessageEvent;
import com.saas.omnicanal.whatsapp.model.InboundMediaEvent;
import com.saas.omnicanal.whatsapp.model.MediaKind;
import org.springframework.context.ApplicationEventPublisher;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Webhook de WhatsApp Cloud API. Cada POST se valida con X-Hub-Signature-256 (HMAC-SHA256 del cuerpo
 * crudo con el app secret de la tienda) y cada cambio se enruta por metadata.phone_number_id.
 */
@Service
public class CloudWebhookService {
    private final CloudApiService cloud;
    private final WhatsAppMessageRepository messages;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;

    public CloudWebhookService(CloudApiService cloud, WhatsAppMessageRepository messages, ObjectMapper json,
                               ApplicationEventPublisher events) {
        this.events = events;
        this.cloud = cloud;
        this.messages = messages;
        this.json = json;
    }

    /** Handshake de suscripción de Meta: devuelve hub.challenge si el verify token coincide. */
    public String verifySubscription(UUID store, String mode, String token, String challenge) {
        if (!"subscribe".equals(mode) || challenge == null || challenge.isBlank() || challenge.length() > 256
                || !Hmac.equalsConstantTime(cloud.verifyToken(store), token))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return challenge;
    }

    public static boolean validSignature(String appSecret, byte[] rawBody, String header) {
        if (appSecret == null || header == null || !header.startsWith("sha256=")) return false;
        return Hmac.equalsConstantTime(Hmac.sha256Hex(appSecret, rawBody), header.substring(7).toLowerCase(java.util.Locale.ROOT));
    }

    @Transactional
    public Map<String, Integer> receive(UUID store, byte[] rawBody, String signature) {
        CloudApiCredentials creds = cloud.credentials(store)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // Sin app secret no hay forma de autenticar el origen: rechazamos en lugar de confiar.
        if (!creds.hasAppSecret()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!validSignature(creds.appSecret(), rawBody, signature)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        JsonNode payload;
        try {
            payload = json.readTree(rawBody);
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        if (!"whatsapp_business_account".equals(payload.path("object").asText(""))) return Map.of("inserted", 0, "updated", 0);
        int inserted = 0;
        int updated = 0;
        for (JsonNode entry : payload.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                JsonNode value = change.path("value");
                // Enrutamiento estricto: solo procesamos cambios del número de esta tienda.
                if (!creds.phoneNumberId().equals(value.path("metadata").path("phone_number_id").asText(""))) continue;
                for (JsonNode message : value.path("messages")) inserted += saveMessage(store, message);
                for (JsonNode status : value.path("statuses")) updated += saveStatus(store, status);
            }
        }
        return Map.of("inserted", inserted, "updated", updated);
    }

    private int saveMessage(UUID store, JsonNode message) {
        String from = message.path("from").asText("");
        String id = message.path("id").asText("");
        if (!from.matches("[0-9]{5,15}") || id.isBlank() || id.length() > 200) return 0;
        String type = message.path("type").asText("");
        MediaKind kind = switch (type) {
            case "audio" -> MediaKind.AUDIO;
            case "image" -> MediaKind.IMAGE;
            case "video" -> MediaKind.VIDEO;
            case "document" -> MediaKind.DOCUMENT;
            default -> null;
        };
        if (kind != null) return saveMedia(store, from, id, kind, message.path(type));
        String body = switch (type) {
            case "text" -> message.path("text").path("body").asText("");
            case "button" -> message.path("button").path("text").asText("");
            case "interactive" -> message.path("interactive").path("button_reply").path("title")
                    .asText(message.path("interactive").path("list_reply").path("title").asText(""));
            default -> "";
        };
        if (body.isBlank()) return 0;
        String chat = ChatIds.fromPhone(from);
        int saved = messages.saveInbound(store, chat, id, body, WhatsAppProvider.CLOUD_API);
        if (saved == 1) events.publishEvent(new InboundMessageEvent(store, chat, id, body));
        return saved;
    }

    private int saveMedia(UUID store, String from, String id, MediaKind kind, JsonNode media) {
        String mediaId = media.path("id").asText("");
        if (mediaId.isBlank() || mediaId.length() > 200) return 0;
        String mime = media.path("mime_type").asText("");
        mime = mime.isBlank() ? null : mime.split(";")[0].strip();
        String caption = media.path("caption").asText("");
        String body = caption.isBlank() ? kind.placeholder() : caption;
        String chat = ChatIds.fromPhone(from);
        int saved = messages.saveInboundMedia(store, chat, id, body, WhatsAppProvider.CLOUD_API, kind, mime,
                caption.isBlank() ? null : caption);
        if (saved == 1) {
            events.publishEvent(new InboundMediaEvent(store, chat, id, WhatsAppProvider.CLOUD_API, kind, mime, null, mediaId));
            events.publishEvent(new InboundMessageEvent(store, chat, id, body, null,
                    WhatsAppMediaService.waitsForAnalysis(kind) ? InboundMessageEvent.Delivery.INBOX : InboundMessageEvent.Delivery.ALL));
        }
        return saved;
    }

    private int saveStatus(UUID store, JsonNode status) {
        String id = status.path("id").asText("");
        String recipient = status.path("recipient_id").asText("");
        String mapped = switch (status.path("status").asText("")) {
            case "delivered" -> "DELIVERED";
            case "read" -> "READ";
            default -> null;
        };
        if (mapped == null || id.isBlank() || id.length() > 200 || !recipient.matches("[0-9]{5,15}")) return 0;
        return messages.saveReceipt(store, ChatIds.fromPhone(recipient), id, mapped);
    }
}
