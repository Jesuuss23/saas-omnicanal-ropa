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
 * Cliente 2: transcripción de notas de voz con Groq Whisper ({@code POST /openai/v1/audio/transcriptions}).
 * Configuración propia: {@code media.transcription.*}, separada del modelo de la IA conversacional.
 */
@Component
public class TranscriptionClient {
    private final boolean enabled;
    private final String apiKey;
    private final String model;
    private final String language;
    private final RestClient http;

    public TranscriptionClient(@Value("${media.transcription.enabled:false}") boolean enabled,
                               @Value("${media.transcription.api-key:}") String apiKey,
                               @Value("${media.transcription.model:whisper-large-v3-turbo}") String model,
                               @Value("${media.transcription.language:es}") String language,
                               @Value("${media.transcription.timeout-ms:30000}") long timeoutMs,
                               @Value("${media.transcription.api-url:https://api.groq.com/openai/v1}") String url) {
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model;
        this.language = language;
        this.http = HttpClients.builder(Duration.ofSeconds(5), Duration.ofMillis(Math.max(1000, timeoutMs)))
                .baseUrl(url.replaceAll("/+$", "")).build();
    }

    public boolean enabled() {
        return enabled && !apiKey.isEmpty();
    }

    /** Devuelve el texto (puede ser vacío si la nota no tiene voz). */
    public String transcribe(byte[] audio, String mimeType) {
        var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("file", Multipart.file(audio, "nota." + Multipart.extension(mimeType), mimeType));
        parts.add("model", model);
        parts.add("language", language);
        parts.add("response_format", "json");
        parts.add("temperature", "0");
        JsonNode body = http.post().uri("/audio/transcriptions").headers(h -> h.setBearerAuth(apiKey))
                .contentType(MediaType.MULTIPART_FORM_DATA).body(parts).retrieve().body(JsonNode.class);
        if (body == null) throw new IllegalStateException("Groq: sin respuesta");
        return body.path("text").asText("").strip();
    }
}
