package com.saas.omnicanal.subscription.service;

import com.saas.omnicanal.shared.web.HttpClients;
import java.util.LinkedHashMap;
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

/**
 * Cliente de la API de Culqi para el cobro recurrente del SaaS (cuenta Culqi de la empresa, no de las tiendas).
 * Las rutas de la API recurrente son configurables para poder ajustarlas a la versión de la cuenta sin tocar código.
 */
@Component
public class CulqiClient {
    private final RestClient api;
    private final String publicKey;
    private final String subscriptionCreatePath;
    private final String subscriptionGetPath;

    public CulqiClient(@Value("${CULQI_SECRET_KEY:}") String secretKey,
                       @Value("${CULQI_PUBLIC_KEY:}") String publicKey,
                       @Value("${culqi.api-url:https://api.culqi.com}") String url,
                       @Value("${culqi.subscriptions.create-path:/v2/recurrent/subscriptions/create}") String createPath,
                       @Value("${culqi.subscriptions.get-path:/v2/recurrent/subscriptions/{id}}") String getPath) {
        this.publicKey = publicKey;
        this.subscriptionCreatePath = createPath;
        this.subscriptionGetPath = getPath;
        this.api = secretKey.isBlank() ? null : HttpClients.standard().baseUrl(url)
                .defaultHeaders(h -> h.setBearerAuth(secretKey)).build();
    }

    public boolean configured() {
        return api != null && !publicKey.isBlank();
    }

    public String publicKey() {
        return publicKey;
    }

    private RestClient api() {
        if (api == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Culqi no está configurado");
        return api;
    }

    public record Customer(String firstName, String lastName, String email, String phone, String address, String city) {}

    public String createCustomer(Customer c) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("first_name", c.firstName());
        body.put("last_name", c.lastName());
        body.put("email", c.email());
        body.put("address", c.address());
        body.put("address_city", c.city());
        body.put("country_code", "PE");
        body.put("phone_number", c.phone());
        return post("/v2/customers", body).path("id").asText();
    }

    public String createCard(String customerId, String tokenId) {
        return post("/v2/cards", Map.of("customer_id", customerId, "token_id", tokenId)).path("id").asText();
    }

    public JsonNode createSubscription(String cardId, String planId, Map<String, String> metadata) {
        return post(subscriptionCreatePath, Map.of("card_id", cardId, "plan_id", planId, "tyc", true, "metadata", metadata));
    }

    public JsonNode subscription(String id) {
        try {
            return api().get().uri(subscriptionGetPath, id).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Culqi respondió " + ex.getStatusCode().value());
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Sin respuesta de Culqi");
        }
    }

    private JsonNode post(String path, Object body) {
        try {
            JsonNode node = api().post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(JsonNode.class);
            if (node == null || node.path("id").asText("").isBlank())
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Culqi no devolvió un id");
            return node;
        } catch (RestClientResponseException ex) {
            // Culqi explica el rechazo (tarjeta, datos) en user_message; se muestra al dueño.
            String message = "Culqi rechazó la operación";
            try {
                JsonNode error = ex.getResponseBodyAs(JsonNode.class);
                if (error != null && !error.path("user_message").asText("").isBlank()) message = error.path("user_message").asText();
            } catch (RuntimeException ignored) {
                // Non-JSON error body.
            }
            throw new ResponseStatusException(ex.getStatusCode().is4xxClientError() ? HttpStatus.UNPROCESSABLE_ENTITY
                    : HttpStatus.BAD_GATEWAY, message);
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Sin respuesta de Culqi");
        }
    }
}
