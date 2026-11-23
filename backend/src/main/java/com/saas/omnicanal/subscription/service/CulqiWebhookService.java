package com.saas.omnicanal.subscription.service;

import com.saas.omnicanal.shared.crypto.Hmac;
import com.saas.omnicanal.shared.webhook.PaymentWebhookEventRepository;
import com.saas.omnicanal.subscription.model.Subscription;
import com.saas.omnicanal.subscription.model.SubscriptionStatus;
import com.saas.omnicanal.subscription.repository.SubscriptionRepository;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Webhook de Culqi para la suscripción del SaaS.
 * 1) Autentica por un token secreto en la URL (configurado al registrar el webhook en CulqiPanel).
 * 2) Registra el evento por id: un reenvío no se procesa dos veces.
 * 3) No confía en el cuerpo: vuelve a consultar la suscripción a Culqi con la llave secreta y comprueba
 *    que pertenezca a la tienda (metadata.tenant_id) antes de cambiar estado o entitlements.
 */
@Service
public class CulqiWebhookService {
    private final String token;
    private final PaymentWebhookEventRepository events;
    private final SubscriptionRepository subscriptions;
    private final CulqiClient culqi;
    private final ObjectMapper json;
    private final int graceDays;

    public CulqiWebhookService(@Value("${CULQI_WEBHOOK_TOKEN:}") String token, PaymentWebhookEventRepository events,
                               SubscriptionRepository subscriptions, CulqiClient culqi, ObjectMapper json,
                               @Value("${subscription.grace-days:5}") int graceDays) {
        this.token = token;
        this.events = events;
        this.subscriptions = subscriptions;
        this.culqi = culqi;
        this.json = json;
        this.graceDays = graceDays;
    }

    enum Outcome { ACTIVATED, PAYMENT_FAILED, CANCELLED, IGNORED }

    /** Mapeo de tipos de evento de Culqi a efectos. Validar los nombres exactos con el sandbox de Culqi. */
    static Outcome classify(String type) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        if (t.contains("cancel") || t.contains("deleted")) return Outcome.CANCELLED;
        if (t.contains("fail") || t.contains("declin") || t.contains("expired")) return Outcome.PAYMENT_FAILED;
        if (t.endsWith("succeeded") || t.endsWith("paid") || t.endsWith("success")) return Outcome.ACTIVATED;
        return Outcome.IGNORED;
    }

    @Transactional
    public Map<String, String> receive(String suppliedToken, String rawBody) {
        if (token.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        if (!Hmac.equalsConstantTime(token, suppliedToken)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        JsonNode event = json.readTree(rawBody);
        String eventId = event.path("id").asText("");
        String type = event.path("type").asText("");
        if (eventId.isBlank() || eventId.length() > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        // Culqi envía "data" como string JSON; se acepta también objeto.
        JsonNode data = event.path("data");
        if (data.isString()) data = json.readTree(data.asString());
        String subscriptionId = subscriptionId(data);
        var local = subscriptionId == null ? null : subscriptions.findByCulqiSubscription(subscriptionId).orElse(null);
        if (!events.register("CULQI", eventId, local == null ? null : local.tenantId(), type, rawBody))
            return Map.of("result", "duplicate");
        Outcome outcome = classify(type);
        if (local == null || outcome == Outcome.IGNORED) {
            events.finish("CULQI", eventId, "IGNORED", local == null ? "Suscripción desconocida" : null);
            return Map.of("result", "ignored");
        }
        JsonNode remote = culqi.subscription(subscriptionId);
        String remoteTenant = remote.path("metadata").path("tenant_id").asText("");
        if (!local.tenantId().toString().equals(remoteTenant)) {
            events.finish("CULQI", eventId, "FAILED", "metadata.tenant_id no coincide");
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        apply(local, outcome, eventId);
        events.finish("CULQI", eventId, "PROCESSED", null);
        return Map.of("result", outcome.name());
    }

    private void apply(Subscription s, Outcome outcome, String eventId) {
        switch (outcome) {
            case ACTIVATED -> {
                String plan = s.pendingPlanCode() != null ? s.pendingPlanCode() : s.planCode();
                OffsetDateTime base = s.currentPeriodEnd() != null && s.currentPeriodEnd().isAfter(OffsetDateTime.now())
                        ? s.currentPeriodEnd() : OffsetDateTime.now();
                subscriptions.activate(s.tenantId(), plan, base.plusMonths(1));
                subscriptions.logEvent(s.tenantId(), s.status(), SubscriptionStatus.ACTIVE, plan, "CULQI_WEBHOOK", eventId);
            }
            case PAYMENT_FAILED -> {
                if (s.status() == SubscriptionStatus.ACTIVE) {
                    subscriptions.setStatus(s.tenantId(), SubscriptionStatus.GRACE, OffsetDateTime.now().plusDays(graceDays));
                    subscriptions.logEvent(s.tenantId(), s.status(), SubscriptionStatus.GRACE, s.planCode(), "CULQI_WEBHOOK", eventId);
                }
            }
            case CANCELLED -> {
                subscriptions.setStatus(s.tenantId(), SubscriptionStatus.CANCELLED, null);
                subscriptions.logEvent(s.tenantId(), s.status(), SubscriptionStatus.CANCELLED, s.planCode(), "CULQI_WEBHOOK", eventId);
            }
            default -> { }
        }
    }

    private static String subscriptionId(JsonNode data) {
        for (String field : new String[]{"subscription_id", "subscriptionId"}) {
            String v = data.path(field).asText("");
            if (!v.isBlank()) return v;
        }
        String v = data.path("subscription").path("id").asText("");
        if (!v.isBlank()) return v;
        String object = data.path("object").asText("");
        String id = data.path("id").asText("");
        return "subscription".equals(object) && !id.isBlank() ? id : null;
    }
}
