package com.saas.omnicanal.notifications.service;

import com.saas.omnicanal.notifications.model.EmailDeliveryException;
import com.saas.omnicanal.notifications.model.EmailMessage;
import com.saas.omnicanal.notifications.repository.EmailLogRepository;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Envía correos con la API de Resend usando {@code RESEND_API_KEY}.
 * <ul>
 *   <li>{@link #send} lanza {@link EmailDeliveryException} si Resend no acepta el correo: el flujo que lo pidió
 *       (registro, recuperación, invitación) responde error y el frontend no muestra un éxito falso.</li>
 *   <li>{@link #sendQuietly} es para avisos secundarios (bienvenida, alertas, campañas) que no deben romper la operación.</li>
 * </ul>
 * Sin {@code RESEND_API_KEY} (desarrollo y pruebas) el correo no sale: se guarda en email_log y se avisa con WARN.
 */
@Service
public class EmailService {
    /** Remitente de pruebas de Resend: funciona sin dominio verificado (solo entrega al correo dueño de la cuenta Resend). */
    public static final String RESEND_TEST_SENDER = "Omnicanal <onboarding@resend.dev>";
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private final EmailLogRepository logs;
    private final ObjectMapper json;
    private final String apiKey;
    private final String from;
    private final String replyTo;
    private final String apiUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public EmailService(EmailLogRepository logs, ObjectMapper json,
                        @Value("${email.resend-api-key:}") String apiKey,
                        @Value("${email.from:}") String from,
                        @Value("${email.reply-to:}") String replyTo,
                        @Value("${email.api-url:https://api.resend.com}") String apiUrl) {
        this.logs = logs;
        this.json = json;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.from = from == null || from.isBlank() ? RESEND_TEST_SENDER : from.strip();
        this.replyTo = replyTo == null ? "" : replyTo.strip();
        this.apiUrl = apiUrl.replaceAll("/+$", "");
    }

    @PostConstruct
    void announce() {
        if (configured()) {
            log.info("Correo: Resend activo (clave {}…, remitente '{}')", apiKey.substring(0, Math.min(6, apiKey.length())), from);
            if (!from.contains("@resend.dev"))
                log.info("Correo: el remitente '{}' necesita su dominio verificado en Resend; para pruebas usa onboarding@resend.dev", from);
        } else {
            log.warn("Correo: RESEND_API_KEY no está configurada. Los correos NO se envían; solo quedan en email_log y en este log.");
        }
    }

    public boolean configured() {
        return !apiKey.isEmpty();
    }

    /** Envía y lanza {@link EmailDeliveryException} si Resend no acepta el correo. */
    public void send(EmailMessage message) {
        if (!configured()) {
            log.warn("Correo '{}' para {} NO enviado: falta RESEND_API_KEY. Contenido:\n{}", message.template(), message.to(), message.text());
            logs.log(message.to(), message.template(), message.subject(), "LOGGED", null, null, message.text());
            return;
        }
        HttpResponse<String> response;
        try {
            var body = new LinkedHashMap<String, Object>();
            body.put("from", from);
            body.put("to", List.of(message.to()));
            body.put("subject", message.subject());
            body.put("html", message.html());
            body.put("text", message.text());
            if (!replyTo.isEmpty()) body.put("reply_to", replyTo);
            var request = HttpRequest.newBuilder(URI.create(apiUrl + "/emails"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            log.info("Resend: enviando '{}' a {} desde '{}'", message.template(), message.to(), from);
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("Resend: no se pudo conectar para enviar '{}' a {}", message.template(), message.to(), ex);
            logs.log(message.to(), message.template(), message.subject(), "FAILED", null, ex.getClass().getSimpleName(), null);
            throw new EmailDeliveryException("Sin conexión con Resend: " + ex.getClass().getSimpleName(), ex);
        }
        if (response.statusCode() / 100 == 2) {
            String id = json.readTree(response.body()).path("id").asText("");
            log.info("Resend: aceptado '{}' para {} (id {})", message.template(), message.to(), id);
            logs.log(message.to(), message.template(), message.subject(), "SENT", id, null, null);
            return;
        }
        String reason = "HTTP " + response.statusCode() + ": " + response.body();
        log.error("Resend: rechazó '{}' para {} desde '{}' → {}", message.template(), message.to(), from, reason);
        logs.log(message.to(), message.template(), message.subject(), "FAILED", null, reason, null);
        throw new EmailDeliveryException("Resend rechazó el correo (" + reason + ")", null);
    }

    /** Para avisos secundarios: registra el fallo y devuelve false en vez de lanzar. */
    public boolean sendQuietly(EmailMessage message) {
        try {
            send(message);
            return true;
        } catch (EmailDeliveryException ex) {
            return false;
        }
    }
}
