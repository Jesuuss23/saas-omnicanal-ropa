package com.saas.omnicanal.media;

import com.saas.omnicanal.shared.web.HttpClients;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Cliente 3: modelo de visión independiente (Gemini Flash-Lite por defecto) que solo devuelve atributos en JSON.
 * Usa su propia clave, modelo y timeout ({@code media.vision.*}): no comparte cuota ni contexto con la IA conversacional.
 */
@Component
public class VisionClient {
    /** Atributos que el modelo extrae de la foto. */
    public record Analysis(boolean esConsultaValidaTienda, String tipoPrenda, String subcategoria, String color,
                           String estilo, String resumen) {}

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "OBJECT",
            "properties", Map.of(
                    "esConsultaValidaTienda", Map.of("type", "BOOLEAN"),
                    "tipoPrenda", Map.of("type", "STRING"),
                    "subcategoria", Map.of("type", "STRING"),
                    "color", Map.of("type", "STRING"),
                    "estilo", Map.of("type", "STRING"),
                    "resumen", Map.of("type", "STRING")),
            "required", List.of("esConsultaValidaTienda", "tipoPrenda", "color", "resumen"));

    private final boolean enabled;
    private final String apiKey;
    private final String model;
    private final RestClient http;
    private final ObjectMapper json = new ObjectMapper();

    public VisionClient(@Value("${media.vision.enabled:false}") boolean enabled,
                        @Value("${media.vision.api-key:}") String apiKey,
                        @Value("${media.vision.model:gemini-flash-lite-latest}") String model,
                        @Value("${media.vision.timeout-ms:20000}") long timeoutMs,
                        @Value("${media.vision.api-url:https://generativelanguage.googleapis.com/v1beta}") String url) {
        this.enabled = enabled;
        this.apiKey = apiKey == null || "sin-configurar".equals(apiKey.strip()) ? "" : apiKey.strip();
        this.model = model;
        this.http = HttpClients.builder(Duration.ofSeconds(5), Duration.ofMillis(Math.max(1000, timeoutMs)))
                .baseUrl(url.replaceAll("/+$", "")).build();
    }

    public boolean enabled() {
        return enabled && !apiKey.isEmpty();
    }

    /** {@code categories}: categorías y subcategorías de la tienda, para que el modelo use los mismos nombres. */
    public Analysis analyze(byte[] image, String mimeType, String caption, String categories) {
        String prompt = """
                Eres el analista de fotos de una tienda de ropa en Perú. Mira la imagen que envió un cliente por WhatsApp y
                responde solo el JSON pedido, en español.
                - esConsultaValidaTienda: true si la foto muestra ropa, calzado o accesorios de vestir (una prenda, un look,
                  una captura de una prenda o una etiqueta de talla); false si es otra cosa (paisajes, comida, memes,
                  documentos, personas sin interés en una prenda, etc.).
                - tipoPrenda: el tipo de prenda en singular y en minúsculas (por ejemplo "polo", "jean", "casaca", "vestido").
                - subcategoria: estilo o línea si se reconoce (por ejemplo "retro", "oversize", "verano"); vacío si no.
                - color: color principal en español.
                - estilo: 2 a 5 palabras sobre corte, estampado o material.
                - resumen: una frase de lo que se ve, sin inventar marcas ni precios.
                Categorías y subcategorías que vende la tienda (usa estos nombres cuando correspondan): %s
                Texto que el cliente escribió con la foto: %s
                """.formatted(categories == null || categories.isBlank() ? "(sin datos)" : categories,
                caption == null || caption.isBlank() ? "(ninguno)" : caption);
        var body = Map.of(
                "contents", List.of(Map.of("role", "user", "parts", List.of(
                        Map.of("inline_data", Map.of("mime_type", mimeType, "data", Base64.getEncoder().encodeToString(image))),
                        Map.of("text", prompt)))),
                "generationConfig", Map.of("responseMimeType", "application/json", "responseSchema", SCHEMA,
                        "temperature", 0.1, "maxOutputTokens", 400));
        JsonNode res = http.post().uri("/models/{model}:generateContent", model).header("x-goog-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
        String text = res == null ? "" : res.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
        if (text.isBlank()) throw new IllegalStateException("El modelo de visión no devolvió datos");
        return parse(json.readTree(text));
    }

    public static Analysis parse(JsonNode n) {
        return new Analysis(n.path("esConsultaValidaTienda").asBoolean(false), clean(n, "tipoPrenda", 40),
                clean(n, "subcategoria", 50), clean(n, "color", 30), clean(n, "estilo", 80), clean(n, "resumen", 300));
    }

    private static String clean(JsonNode n, String field, int max) {
        String v = n.path(field).asText("").strip();
        return v.length() > max ? v.substring(0, max) : v;
    }
}
