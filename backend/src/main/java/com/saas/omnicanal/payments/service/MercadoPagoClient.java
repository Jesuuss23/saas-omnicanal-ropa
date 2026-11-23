package com.saas.omnicanal.payments.service;

import com.saas.omnicanal.shared.web.HttpClients;
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
 * Cliente de Mercado Pago. MP_CLIENT_ID / MP_CLIENT_SECRET son de la aplicación del SaaS (marketplace);
 * los cobros usan el access token OAuth de cada tienda, así el dinero llega a la cuenta del dueño.
 */
@Component
public class MercadoPagoClient {
    private final RestClient api;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;
    private final String authUrl;

    public MercadoPagoClient(@Value("${MP_CLIENT_ID:}") String clientId, @Value("${MP_CLIENT_SECRET:}") String clientSecret,
                             @Value("${MP_REDIRECT_URI:}") String redirectUri,
                             @Value("${mercadopago.api-url:https://api.mercadopago.com}") String apiUrl,
                             @Value("${mercadopago.auth-url:https://auth.mercadopago.com/authorization}") String authUrl) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        this.authUrl = authUrl;
        this.api = HttpClients.standard().baseUrl(apiUrl).build();
    }

    public boolean configured() {
        return !clientId.isBlank() && !clientSecret.isBlank() && !redirectUri.isBlank();
    }

    public String authorizationUrl(String state) {
        return org.springframework.web.util.UriComponentsBuilder.fromUriString(authUrl)
                .queryParam("client_id", clientId).queryParam("response_type", "code")
                .queryParam("platform_id", "mp").queryParam("state", state)
                .queryParam("redirect_uri", redirectUri).encode().build().toUriString();
    }

    public record Tokens(String accessToken, String refreshToken, long expiresIn, long userId, String publicKey, boolean liveMode) {
        @Override public String toString() { return "Tokens[userId=" + userId + "]"; }
    }

    public Tokens exchangeCode(String code) {
        return token(Map.of("client_id", clientId, "client_secret", clientSecret, "grant_type", "authorization_code",
                "code", code, "redirect_uri", redirectUri));
    }

    public Tokens refresh(String refreshToken) {
        return token(Map.of("client_id", clientId, "client_secret", clientSecret, "grant_type", "refresh_token",
                "refresh_token", refreshToken));
    }

    private Tokens token(Map<String, String> body) {
        JsonNode node = call(() -> api.post().uri("/oauth/token").contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(JsonNode.class));
        if (node.path("access_token").asText("").isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        return new Tokens(node.path("access_token").asText(), node.path("refresh_token").asText(null),
                node.path("expires_in").asLong(0), node.path("user_id").asLong(), node.path("public_key").asText(null),
                node.path("live_mode").asBoolean(false));
    }

    public JsonNode createPreference(String sellerToken, Map<String, Object> preference, String idempotencyKey) {
        return call(() -> api.post().uri("/checkout/preferences").contentType(MediaType.APPLICATION_JSON)
                .headers(h -> { h.setBearerAuth(sellerToken); h.set("X-Idempotency-Key", idempotencyKey); })
                .body(preference).retrieve().body(JsonNode.class));
    }

    public JsonNode payment(String sellerToken, String paymentId) {
        return call(() -> api.get().uri("/v1/payments/{id}", paymentId)
                .headers(h -> h.setBearerAuth(sellerToken)).retrieve().body(JsonNode.class));
    }

    private JsonNode call(java.util.function.Supplier<JsonNode> request) {
        try {
            JsonNode node = request.get();
            if (node == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Respuesta vacía de Mercado Pago");
            return node;
        } catch (RestClientResponseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Mercado Pago respondió " + ex.getStatusCode().value());
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Sin respuesta de Mercado Pago");
        }
    }
}
