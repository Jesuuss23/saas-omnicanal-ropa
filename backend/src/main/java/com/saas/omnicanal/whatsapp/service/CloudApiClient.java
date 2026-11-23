package com.saas.omnicanal.whatsapp.service;

import com.saas.omnicanal.shared.web.HttpClients;
import com.saas.omnicanal.whatsapp.model.CloudApiCredentials;
import com.saas.omnicanal.whatsapp.model.SendResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Cliente de WhatsApp Cloud API (Graph API de Meta). Cada llamada usa el token de la tienda;
 * no hay credenciales globales de Meta en el servidor.
 */
@Component
public class CloudApiClient {
    private final RestClient graph;
    private final ObjectMapper json;

    public CloudApiClient(@Value("${whatsapp.cloud.graph-url:https://graph.facebook.com}") String baseUrl,
                          @Value("${whatsapp.cloud.graph-version:v23.0}") String version, ObjectMapper json) {
        this.graph = HttpClients.standard().baseUrl(baseUrl.replaceAll("/+$", "") + "/" + version).build();
        this.json = json;
    }

    /** Error legible de Graph API (nunca incluye el token). */
    public static class GraphException extends RuntimeException {
        private final int status;

        GraphException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() { return status; }
    }

    public record PhoneNumber(String displayPhoneNumber, String verifiedName, String qualityRating) {}

    public PhoneNumber phoneNumber(CloudApiCredentials c) {
        JsonNode node = call(() -> graph.get()
                .uri(u -> u.path("/{id}").queryParam("fields", "display_phone_number,verified_name,quality_rating")
                        .build(c.phoneNumberId()))
                .headers(h -> h.setBearerAuth(c.accessToken())).retrieve().body(JsonNode.class));
        return new PhoneNumber(text(node, "display_phone_number"), text(node, "verified_name"), text(node, "quality_rating"));
    }

    public record Template(String id, String name, String language, String category, String status, String componentsJson) {}

    /** Lista todas las plantillas de la WABA, siguiendo la paginación de Graph API (máx. 10 páginas). */
    public List<Template> templates(CloudApiCredentials c) {
        List<Template> result = new ArrayList<>();
        String after = null;
        for (int page = 0; page < 10; page++) {
            final String cursor = after;
            JsonNode node = call(() -> graph.get().uri(u -> {
                        var b = u.path("/{waba}/message_templates")
                                .queryParam("fields", "id,name,language,status,category,components")
                                .queryParam("limit", 100);
                        if (cursor != null) b.queryParam("after", cursor);
                        return b.build(c.wabaId());
                    }).headers(h -> h.setBearerAuth(c.accessToken())).retrieve().body(JsonNode.class));
            for (JsonNode t : node.path("data")) {
                result.add(new Template(t.path("id").asText(), t.path("name").asText(), t.path("language").asText(),
                        text(t, "category"), t.path("status").asText("UNKNOWN"),
                        json.writeValueAsString(t.path("components").isArray() ? t.path("components") : json.createArrayNode())));
            }
            after = node.path("paging").path("cursors").path("after").asText("");
            if (after.isBlank() || node.path("paging").path("next").isMissingNode()) break;
        }
        return result;
    }

    public SendResult sendText(CloudApiCredentials c, String to, String body) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("recipient_type", "individual");
        payload.put("to", to);
        payload.put("type", "text");
        payload.put("text", Map.of("preview_url", false, "body", body));
        return send(c, payload);
    }

    public SendResult sendTemplate(CloudApiCredentials c, String to, String name, String language, List<String> bodyParams) {
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("name", name);
        template.put("language", Map.of("code", language));
        if (bodyParams != null && !bodyParams.isEmpty()) {
            template.put("components", List.of(Map.of("type", "body",
                    "parameters", bodyParams.stream().map(p -> Map.of("type", "text", "text", p)).toList())));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("recipient_type", "individual");
        payload.put("to", to);
        payload.put("type", "template");
        payload.put("template", template);
        return send(c, payload);
    }

    private SendResult send(CloudApiCredentials c, Map<String, Object> payload) {
        try {
            JsonNode node = graph.post().uri("/{id}/messages", c.phoneNumberId())
                    .headers(h -> h.setBearerAuth(c.accessToken())).contentType(MediaType.APPLICATION_JSON)
                    .body(payload).retrieve().body(JsonNode.class);
            String id = node == null ? "" : node.path("messages").path(0).path("id").asText("");
            return id.isBlank() ? SendResult.unknown("Meta no devolvió el id del mensaje") : SendResult.accepted(id);
        } catch (RestClientResponseException ex) {
            String error = describe(ex);
            return ex.getStatusCode().is4xxClientError() ? SendResult.rejected(error) : SendResult.unknown(error);
        } catch (RestClientException ex) {
            return SendResult.unknown("Sin respuesta de Meta");
        }
    }

    private JsonNode call(java.util.function.Supplier<JsonNode> request) {
        try {
            JsonNode node = request.get();
            if (node == null) throw new GraphException(502, "Respuesta vacía de Meta");
            return node;
        } catch (RestClientResponseException ex) {
            throw new GraphException(ex.getStatusCode().value(), describe(ex));
        } catch (RestClientException ex) {
            throw new GraphException(502, "Sin respuesta de Meta");
        }
    }

    private String describe(RestClientResponseException ex) {
        try {
            JsonNode error = json.readTree(ex.getResponseBodyAsString()).path("error");
            String message = error.path("message").asText("");
            String code = error.path("code").asText("");
            if (!message.isBlank()) return (code.isBlank() ? "" : "(#" + code + ") ") + message;
        } catch (RuntimeException ignored) {
            // Non-JSON error body.
        }
        return "HTTP " + ex.getStatusCode().value();
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("");
        return value.isBlank() ? null : value;
    }
}
