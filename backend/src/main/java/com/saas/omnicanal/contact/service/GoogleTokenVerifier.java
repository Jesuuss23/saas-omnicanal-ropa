package com.saas.omnicanal.contact.service;

import com.saas.omnicanal.contact.model.GoogleIdentity;
import com.saas.omnicanal.shared.web.HttpClients;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifica el ID token que entrega el botón "Continuar con Google" (Google Identity Services):
 * firma RS256 con las llaves públicas de Google, emisor, audiencia (nuestro client id), vigencia y correo verificado.
 */
@Service
public class GoogleTokenVerifier {
    private static final Set<String> ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");
    private static final Duration KEYS_TTL = Duration.ofHours(6);

    private final String clientId;
    private final ObjectMapper json;
    private final RestClient http = HttpClients.standard().build();
    private volatile Map<String, PublicKey> keys = Map.of();
    private volatile Instant keysFetchedAt = Instant.EPOCH;

    public GoogleTokenVerifier(@Value("${customers.google-client-id:}") String clientId, ObjectMapper json) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.json = json;
    }

    public boolean configured() {
        return !clientId.isEmpty();
    }

    public String clientId() {
        return configured() ? clientId : null;
    }

    public Optional<GoogleIdentity> verify(String idToken) {
        if (!configured() || idToken == null || idToken.length() > 4096) return Optional.empty();
        String[] parts = idToken.split("\\.");
        if (parts.length != 3) return Optional.empty();
        try {
            JsonNode header = json.readTree(decode(parts[0]));
            JsonNode claims = json.readTree(decode(parts[1]));
            if (!"RS256".equals(header.path("alg").asString())) return Optional.empty();
            PublicKey key = key(header.path("kid").asString());
            if (key == null) return Optional.empty();
            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initVerify(key);
            rsa.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!rsa.verify(Base64.getUrlDecoder().decode(parts[2]))) return Optional.empty();

            long now = Instant.now().getEpochSecond();
            boolean valid = ISSUERS.contains(claims.path("iss").asString())
                    && clientId.equals(claims.path("aud").asString())
                    && claims.path("exp").asLong() > now - 30
                    && claims.path("iat").asLong() < now + 300
                    && claims.path("email_verified").asBoolean(false)
                    && !claims.path("sub").asString().isBlank()
                    && claims.path("email").asString().contains("@");
            if (!valid) return Optional.empty();
            String name = claims.path("name").asString();
            return Optional.of(new GoogleIdentity(claims.path("sub").asString(), claims.path("email").asString().toLowerCase(),
                    name == null || name.isBlank() ? null : name.strip()));
        } catch (Exception malformed) {
            return Optional.empty();
        }
    }

    private PublicKey key(String kid) throws Exception {
        if (kid == null || kid.isBlank()) return null;
        PublicKey key = keys.get(kid);
        boolean stale = Instant.now().isAfter(keysFetchedAt.plus(KEYS_TTL));
        // Google rota sus llaves: si el kid no está, se recarga (como máximo una vez por minuto).
        if (key == null || stale) {
            if (stale || Instant.now().isAfter(keysFetchedAt.plusSeconds(60))) refreshKeys();
            key = keys.get(kid);
        }
        return key;
    }

    private synchronized void refreshKeys() throws Exception {
        JsonNode body = http.get().uri("https://www.googleapis.com/oauth2/v3/certs").retrieve().body(JsonNode.class);
        Map<String, PublicKey> fresh = new HashMap<>();
        KeyFactory rsa = KeyFactory.getInstance("RSA");
        for (JsonNode k : body.path("keys")) {
            if (!"RSA".equals(k.path("kty").asString())) continue;
            var spec = new RSAPublicKeySpec(new BigInteger(1, Base64.getUrlDecoder().decode(k.path("n").asString())),
                    new BigInteger(1, Base64.getUrlDecoder().decode(k.path("e").asString())));
            fresh.put(k.path("kid").asString(), rsa.generatePublic(spec));
        }
        keys = Map.copyOf(fresh);
        keysFetchedAt = Instant.now();
    }

    private static String decode(String part) {
        return new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
    }
}
