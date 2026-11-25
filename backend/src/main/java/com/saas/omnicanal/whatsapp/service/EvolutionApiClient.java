package com.saas.omnicanal.whatsapp.service;

import com.saas.omnicanal.shared.web.HttpClients;
import com.saas.omnicanal.whatsapp.model.SendResult;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/** Cliente HTTP de Evolution API (conexión por QR). Una instancia por tienda: "tienda-{tenantId}". */
@Component
public class EvolutionApiClient {
    private final RestClient api;

    public EvolutionApiClient(@Value("${EVOLUTION_API_URL:}") String url, @Value("${EVOLUTION_API_KEY:}") String key) {
        api = url.isBlank() || key.isBlank() ? null
                : HttpClients.standard().baseUrl(url).defaultHeader("apikey", key).build();
    }

    public boolean configured() {
        return api != null;
    }

    public static String instanceName(java.util.UUID tenant) {
        return "tienda-" + tenant;
    }

    private RestClient api() {
        if (api == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return api;
    }

    /** CONNECTED, PAIRING, DISCONNECTED o UNLINKED (la instancia no existe). */
    public String state(String name) {
        try {
            JsonNode result = api().get().uri("/instance/connectionState/{name}", name).retrieve().body(JsonNode.class);
            String state = result == null ? "" : result.path("instance").path("state").asText(result.path("state").asText(""));
            String mapped = mapState(state);
            if (mapped == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
            return mapped;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) return "UNLINKED";
            throw ex;
        }
    }

    /** Estado de Evolution ({@code open}, {@code connecting}, {@code close}) a nuestro estado, o null si es desconocido. */
    public static String mapState(String state) {
        return switch (state == null ? "" : state.toLowerCase(java.util.Locale.ROOT)) {
            case "open" -> "CONNECTED";
            case "connecting" -> "PAIRING";
            case "close", "closed", "refused" -> "DISCONNECTED";
            default -> null;
        };
    }

    public String createInstance(String name) {
        JsonNode created = api().post().uri("/instance/create").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("instanceName", name, "integration", "WHATSAPP-BAILEYS", "qrcode", true))
                .retrieve().body(JsonNode.class);
        return qrImage(created == null ? null : created.path("qrcode"));
    }

    public String connect(String name) {
        return qrImage(api().get().uri("/instance/connect/{name}", name).retrieve().body(JsonNode.class));
    }

    public void setWebhook(String name, String url, Map<String, String> headers) {
        var settings = Map.of("enabled", true, "url", url, "headers", headers, "byEvents", false, "base64", false,
                "events", List.of("MESSAGES_UPSERT", "MESSAGES_UPDATE", "CONNECTION_UPDATE"));
        api().post().uri("/webhook/set/{name}", name).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("webhook", settings)).retrieve().toBodilessEntity();
    }

    /** {@code number}: el remoteJid del chat tal cual ({@code …@lid} o {@code …@s.whatsapp.net}). */
    public SendResult sendText(String name, String number, String text) {
        try {
            JsonNode result = api().post().uri("/message/sendText/{name}", name).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("number", number, "text", text)).retrieve().body(JsonNode.class);
            String id = result == null ? "" : result.path("key").path("id").asText("");
            return id.isBlank() ? SendResult.unknown(null) : SendResult.accepted(id);
        } catch (RestClientResponseException ex) {
            int code = ex.getStatusCode().value();
            if (code == 400 || code == 401 || code == 403 || code == 404 || code == 422)
                return SendResult.rejected("HTTP " + code);
            return SendResult.unknown("HTTP " + code);
        } catch (RestClientException uncertain) {
            // Evolution may have accepted the message before the connection failed.
            return SendResult.unknown(null);
        }
    }

    public record Downloaded(byte[] content, String mimeType, String fileName) {}

    /**
     * Archivo de un mensaje recibido. Usa el base64 del propio webhook si viene (webhook con base64 activo); si no,
     * lo pide a {@code POST /chat/getBase64FromMediaMessage/{instance}} con el mensaje completo (key + message).
     */
    public Downloaded media(String name, JsonNode item) {
        JsonNode message = item.path("message");
        String inline = message.path("base64").asText("");
        String mime = mimeOf(message);
        if (inline.isBlank()) {
            JsonNode result = api().post().uri("/chat/getBase64FromMediaMessage/{name}", name).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("message", Map.of("key", item.path("key"), "message", message), "convertToMp4", false))
                    .retrieve().body(JsonNode.class);
            inline = result == null ? "" : result.path("base64").asText("");
            if (result != null && !result.path("mimetype").asText("").isBlank()) mime = result.path("mimetype").asText();
        }
        String data = inline.contains(",") && inline.startsWith("data:") ? inline.substring(inline.indexOf(',') + 1) : inline;
        if (data.isBlank()) throw new IllegalStateException("Evolution no devolvió el archivo");
        return new Downloaded(java.util.Base64.getMimeDecoder().decode(data), mime, null);
    }

    /** Mime declarado en imageMessage / videoMessage / audioMessage / documentMessage. */
    public static String mimeOf(JsonNode message) {
        for (String field : List.of("imageMessage", "videoMessage", "audioMessage", "documentMessage", "stickerMessage")) {
            String mime = message.path(field).path("mimetype").asText("");
            if (!mime.isBlank()) return mime.split(";")[0].strip();
        }
        return "application/octet-stream";
    }

    /**
     * Envía un archivo con {@code POST /message/sendMedia/{instance}}. {@code mediatype}: image, video o document.
     * {@code number}: el remoteJid del chat tal cual.
     */
    public SendResult sendMedia(String name, String number, String mediatype, String mimeType, String fileName, byte[] content,
                                String caption) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("number", number);
        body.put("mediatype", mediatype);
        body.put("mimetype", mimeType);
        body.put("media", java.util.Base64.getEncoder().encodeToString(content));
        body.put("fileName", fileName == null ? "archivo" : fileName);
        if (caption != null && !caption.isBlank()) body.put("caption", caption);
        try {
            JsonNode result = api().post().uri("/message/sendMedia/{name}", name).contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(JsonNode.class);
            String id = result == null ? "" : result.path("key").path("id").asText("");
            return id.isBlank() ? SendResult.unknown(null) : SendResult.accepted(id);
        } catch (RestClientResponseException ex) {
            int code = ex.getStatusCode().value();
            if (code == 400 || code == 401 || code == 403 || code == 404 || code == 413 || code == 422)
                return SendResult.rejected("HTTP " + code);
            return SendResult.unknown("HTTP " + code);
        } catch (RestClientException uncertain) {
            return SendResult.unknown(null);
        }
    }

    static String qrImage(JsonNode qr) {
        if (qr == null) return null;
        String image = qr.path("base64").asText("");
        String prefix = "data:image/png;base64,";
        String data = image.startsWith(prefix) ? image.substring(prefix.length()) : image;
        return data.startsWith("iVBORw0KGgo") && data.length() < 512000 ? prefix + data : null;
    }
}
