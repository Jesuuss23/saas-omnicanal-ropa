package com.saas.omnicanal.assistant.service;

import com.saas.omnicanal.assistant.model.AssistantModels.Answer;
import com.saas.omnicanal.assistant.model.AssistantModels.StoreFacts;
import com.saas.omnicanal.assistant.model.AssistantModels.Turn;
import com.saas.omnicanal.catalog.repository.PublicCatalogRepository;
import com.google.genai.errors.ApiException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/** IA vendedora con Gemini (Spring AI) y llamadas a herramientas sobre el catálogo de la tienda. */
@Service
public class GeminiResponder implements AssistantResponder {
    private final ObjectProvider<ChatClient.Builder> builders;
    private final PublicCatalogRepository catalog;
    private static final Logger log = LoggerFactory.getLogger(GeminiResponder.class);
    private final String apiKey;
    private final String model;
    private final String apiBase;
    private final String fallbackModel;
    private final long retryDelayMs;

    public GeminiResponder(ObjectProvider<ChatClient.Builder> builders, PublicCatalogRepository catalog,
                           @Value("${spring.ai.google.genai.api-key:}") String apiKey,
                           @Value("${spring.ai.google.genai.chat.model:gemini-3.5-flash}") String model,
                           @Value("${assistant.gemini-api-base:https://generativelanguage.googleapis.com/v1beta}") String apiBase,
                           @Value("${assistant.gemini-fallback-model:gemini-flash-latest}") String fallbackModel,
                           @Value("${assistant.gemini-retry-delay-ms:1500}") long retryDelayMs) {
        this.builders = builders;
        this.catalog = catalog;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.model = model == null || model.isBlank() ? "gemini-3.5-flash" : model.strip();
        this.apiBase = apiBase.replaceAll("/+$", "");
        this.fallbackModel = fallbackModel == null || fallbackModel.isBlank() ? this.model : fallbackModel.strip();
        this.retryDelayMs = Math.max(0, retryDelayMs);
    }

    public String model() {
        return model;
    }

    /** 429 (cuota por minuto), 500, 503 (alta demanda) y 504 suelen pasar solos: vale la pena reintentar. */
    static boolean transientFailure(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ApiException api) return api.code() == 429 || api.code() >= 500;
            if (t.getCause() == t) break;
        }
        return false;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Al arrancar confirma que el modelo existe para esta clave; si no, deja en ERROR la respuesta de Google y los modelos disponibles. */
    @EventListener(ApplicationReadyEvent.class)
    public void verifyModel() {
        if (!configured()) {
            log.warn("IA vendedora: GOOGLE_GENAI_API_KEY no está configurada; la IA queda apagada.");
            return;
        }
        var check = new Thread(this::checkModel, "gemini-check");
        check.setDaemon(true);
        check.start();
    }

    public boolean checkModel() {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        try {
            var res = http.send(get("/models/" + model), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                log.info("IA vendedora: modelo Gemini '{}' disponible", model);
                return true;
            }
            log.error("IA vendedora: Gemini respondió HTTP {} para el modelo '{}': {}", res.statusCode(), model, res.body());
            var list = http.send(get("/models?pageSize=200"), HttpResponse.BodyHandlers.ofString());
            if (list.statusCode() == 200) {
                var names = new java.util.TreeSet<String>();
                var m = java.util.regex.Pattern.compile("\"name\"\\s*:\\s*\"models/([^\"]+)\"").matcher(list.body());
                while (m.find()) if (m.group(1).contains("flash") || m.group(1).contains("pro")) names.add(m.group(1));
                log.error("IA vendedora: modelos disponibles para tu clave (usa uno en GEMINI_MODEL): {}", names);
            } else {
                log.error("IA vendedora: no se pudo listar modelos, HTTP {}: {}", list.statusCode(), list.body());
            }
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("IA vendedora: no se pudo contactar a Gemini para verificar el modelo '{}'", model, ex);
        }
        return false;
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(URI.create(apiBase + path)).timeout(Duration.ofSeconds(10))
                .header("x-goog-api-key", apiKey).GET().build();
    }

    /** Código HTTP, estado y mensaje de Google si el error viene de la API; si no, el mensaje de la excepción. */
    public static String describe(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ApiException api)
                return "Gemini HTTP " + api.code() + " " + api.status() + ": " + api.message();
            if (t.getCause() == t) break;
        }
        return ex.getClass().getSimpleName() + ": " + ex.getMessage();
    }

    @Override
    public boolean configured() {
        return !apiKey.isEmpty() && !"sin-configurar".equals(apiKey) && builders.getIfAvailable() != null;
    }

    @Override
    public Answer answer(UUID tenant, StoreFacts facts, String instructions, List<Turn> history, String note, String message) {
        var tools = new StoreTools(tenant, catalog, facts);
        List<Message> messages = new ArrayList<>();
        for (Turn t : history) messages.add("CUSTOMER".equals(t.author()) ? new UserMessage(t.text()) : new AssistantMessage(t.text()));
        String text = null;
        RuntimeException last = null;
        // Gemini responde 503/429 cuando el modelo está saturado: reintenta con espera y, al final, usa el modelo de respaldo.
        String[] plan = fallbackModel.equals(model) ? new String[]{model, model, model} : new String[]{model, model, fallbackModel};
        for (int attempt = 0; attempt < plan.length; attempt++) {
            String current = plan[attempt];
            try {
                text = builders.getObject().build().prompt()
                        .options(GoogleGenAiChatOptions.builder().model(current))
                        .system(system(facts, instructions, note))
                        .messages(messages)
                        .user(message)
                        .tools(tools)
                        .call().content();
                last = null;
                break;
            } catch (RuntimeException ex) {
                last = ex;
                boolean transient_ = transientFailure(ex);
                log.error("IA vendedora: falló la llamada a Gemini (modelo '{}', intento {}/{}) → {}", current, attempt + 1,
                        plan.length, describe(ex), transient_ ? null : ex);
                if (!transient_ || attempt == plan.length - 1) break;
                sleep(retryDelayMs * (1L << attempt));
            }
        }
        if (last != null) throw last;
        boolean escalate = tools.escalationReason != null;
        if (text == null || text.isBlank()) text = escalate ? "Gracias por escribirnos. Una persona del equipo te responderá pronto."
                : "Gracias por escribirnos. ¿Me cuentas qué prenda buscas y en qué talla?";
        return new Answer(text.strip(), escalate, tools.escalationReason, tools.escalationSummary, List.copyOf(tools.used));
    }

    static String system(StoreFacts facts, String instructions, String note) {
        var s = new StringBuilder();
        s.append("Eres la persona que atiende el WhatsApp de ").append(facts.storeName())
                .append(", una tienda de ropa en Perú. Respondes a clientes que quieren comprar.\n")
                .append("Reglas:\n")
                .append("- Escribe en español de Perú, cordial y breve: máximo 4 oraciones, sin listas largas.\n")
                .append("- Nunca inventes precios, tallas, colores ni stock: consulta buscarPrendas antes de mencionarlos.\n")
                .append("- Los precios están en soles (S/). Si no hay stock de algo, ofrece alternativas reales del catálogo.\n")
                .append("- Para comprar, comparte el enlace de la prenda o el del catálogo: ").append(facts.catalogUrl()).append("\n")
                .append("- Para recojo o delivery usa infoTienda.\n")
                .append("- No pidas datos de tarjetas ni contraseñas. No prometas descuentos, cambios ni devoluciones.\n")
                .append("- Llama a escalar si el cliente pide hablar con una persona, tiene un reclamo, pide descuento, cambio o ")
                .append("devolución, se molesta o no puedes resolver su consulta con las herramientas.\n");
        if (instructions != null && !instructions.isBlank())
            s.append("Indicaciones del dueño de la tienda (respétalas si no contradicen las reglas):\n").append(instructions.strip()).append("\n");
        if (note != null && !note.isBlank())
            s.append("Nota del equipo, que atendió antes esta conversación:\n").append(note.strip()).append("\n");
        return s.toString();
    }
}
