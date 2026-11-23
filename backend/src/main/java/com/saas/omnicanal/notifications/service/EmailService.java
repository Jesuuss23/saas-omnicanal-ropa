package com.saas.omnicanal.notifications.service;

import com.saas.omnicanal.notifications.model.EmailMessage;
import com.saas.omnicanal.notifications.repository.EmailLogRepository;
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
 * Envía correos transaccionales con Resend. Sin API key los deja en email_log (con el cuerpo)
 * y en el log, para desarrollo y pruebas. Nunca lanza: un correo fallido no debe romper el flujo.
 */
@Service
public class EmailService {
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
                        @Value("${email.from:Omnicanal <no-reply@localhost>}") String from,
                        @Value("${email.reply-to:}") String replyTo,
                        @Value("${email.api-url:https://api.resend.com}") String apiUrl) {
        this.logs = logs;
        this.json = json;
        this.apiKey = apiKey.strip();
        this.from = from;
        this.replyTo = replyTo.strip();
        this.apiUrl = apiUrl.replaceAll("/+$", "");
    }

    public boolean configured() {
        return !apiKey.isEmpty();
    }

    public void send(EmailMessage message) {
        if (!configured()) {
            log.info("Correo '{}' para {} (sin proveedor configurado):\n{}", message.template(), message.to(), message.text());
            logs.log(message.to(), message.template(), message.subject(), "LOGGED", null, null, message.text());
            return;
        }
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
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) {
                String id = json.readTree(response.body()).path("id").asText("");
                logs.log(message.to(), message.template(), message.subject(), "SENT", id, null, null);
            } else {
                logs.log(message.to(), message.template(), message.subject(), "FAILED", null,
                        "HTTP " + response.statusCode() + ": " + response.body(), null);
            }
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("No se pudo enviar el correo '{}' a {}", message.template(), message.to(), ex);
            logs.log(message.to(), message.template(), message.subject(), "FAILED", null, ex.getClass().getSimpleName(), null);
        }
    }
}
