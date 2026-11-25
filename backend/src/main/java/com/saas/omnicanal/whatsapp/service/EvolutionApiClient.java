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
            String state = result == null ? "" : result.path("instance").path("state").asText("");
            return switch (state) {
                case "open" -> "CONNECTED";
                case "connecting" -> "PAIRING";
                case "close", "closed" -> "DISCONNECTED";
                default -> throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
            };
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) return "UNLINKED";
            throw ex;
        }
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
                "events", List.of("MESSAGES_UPSERT", "MESSAGES_UPDATE"));
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

    static String qrImage(JsonNode qr) {
        if (qr == null) return null;
        String image = qr.path("base64").asText("");
        String prefix = "data:image/png;base64,";
        String data = image.startsWith(prefix) ? image.substring(prefix.length()) : image;
        return data.startsWith("iVBORw0KGgo") && data.length() < 512000 ? prefix + data : null;
    }
}
