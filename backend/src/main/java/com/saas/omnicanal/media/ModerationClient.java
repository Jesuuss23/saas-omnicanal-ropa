package com.saas.omnicanal.media;

import com.saas.omnicanal.shared.web.HttpClients;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Cliente 1: moderación de contenido explícito con Sightengine ({@code POST /1.0/check.json}, modelo {@code nudity-2.1}).
 * Corre antes de mostrar o analizar una imagen. Configuración propia: {@code media.moderation.*}.
 */
@Component
public class ModerationClient {
    /** {@code score}: la mayor probabilidad entre actividad sexual, exhibición sexual y erotismo (0 a 1). */
    public record Result(boolean blocked, double score) {}

    private final boolean enabled;
    private final String user;
    private final String secret;
    private final double threshold;
    private final RestClient http;

    public ModerationClient(@Value("${media.moderation.enabled:false}") boolean enabled,
                            @Value("${media.moderation.api-user:}") String user,
                            @Value("${media.moderation.api-secret:}") String secret,
                            @Value("${media.moderation.threshold:0.7}") double threshold,
                            @Value("${media.moderation.timeout-ms:10000}") long timeoutMs,
                            @Value("${media.moderation.api-url:https://api.sightengine.com/1.0}") String url) {
        this.enabled = enabled;
        this.user = user == null ? "" : user.strip();
        this.secret = secret == null ? "" : secret.strip();
        this.threshold = threshold;
        this.http = HttpClients.builder(Duration.ofSeconds(5), Duration.ofMillis(Math.max(1000, timeoutMs)))
                .baseUrl(url.replaceAll("/+$", "")).build();
    }

    public boolean enabled() {
        return enabled && !user.isEmpty() && !secret.isEmpty();
    }

    public double threshold() {
        return threshold;
    }

    public Result check(byte[] image, String mimeType) {
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("media", Multipart.file(image, "imagen." + Multipart.extension(mimeType), mimeType));
        parts.add("models", "nudity-2.1");
        parts.add("api_user", user);
        parts.add("api_secret", secret);
        JsonNode body = http.post().uri("/check.json").contentType(MediaType.MULTIPART_FORM_DATA).body(parts)
                .retrieve().body(JsonNode.class);
        if (body == null || !"success".equals(body.path("status").asText("")))
            throw new IllegalStateException("Sightengine: " + (body == null ? "sin respuesta" : body.path("error").path("message").asText("error")));
        return evaluate(body.path("nudity"), threshold);
    }

    /** Público para las pruebas: decide con la respuesta de {@code nudity-2.1}. */
    public static Result evaluate(JsonNode nudity, double threshold) {
        double score = Math.max(nudity.path("sexual_activity").asDouble(0),
                Math.max(nudity.path("sexual_display").asDouble(0), nudity.path("erotica").asDouble(0)));
        score = Math.max(0, Math.min(1, score));
        return new Result(score >= threshold, score);
    }
}
