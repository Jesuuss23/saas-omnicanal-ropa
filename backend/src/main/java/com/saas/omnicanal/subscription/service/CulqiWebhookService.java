package com.saas.omnicanal.subscription.service;

import com.saas.omnicanal.shared.crypto.Hmac;
import com.saas.omnicanal.shared.webhook.PaymentWebhookEventRepository;
import com.saas.omnicanal.subscription.model.Subscription;
import com.saas.omnicanal.subscription.model.SubscriptionStatus;
import com.saas.omnicanal.subscription.repository.PlanRepository;
import com.saas.omnicanal.subscription.repository.SubscriptionRepository;
import java.time.OffsetDateTime;
import java.util.List;
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
 * 3) Ubica la suscripción: por su id (subsId, subscription_id, data.object.id…) o, en los eventos de cargo que no
 *    lo traen, por el correo con que se pagó y el monto o el plan.
 * 4) No confía en el cuerpo: vuelve a consultar la suscripción a Culqi con la llave secreta y, si trae
 *    metadata.tenant_id, comprueba que sea de la tienda antes de cambiar estado o entitlements.
 */
@Service
public class CulqiWebhookService {
    private final String token;
    private final PaymentWebhookEventRepository events;
    private final SubscriptionRepository subscriptions;
    private final CulqiClient culqi;
    private final PlanRepository plans;
    private final ObjectMapper json;
    private final int graceDays;

    public CulqiWebhookService(@Value("${CULQI_WEBHOOK_TOKEN:}") String token, PaymentWebhookEventRepository events,
                               SubscriptionRepository subscriptions, CulqiClient culqi, PlanRepository plans, ObjectMapper json,
                               @Value("${subscription.grace-days:5}") int graceDays) {
        this.token = token;
        this.events = events;
        this.subscriptions = subscriptions;
        this.culqi = culqi;
        this.plans = plans;
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

    /** Lo que se pudo leer del evento, en cualquiera de los formatos que envía Culqi. */
    record Parsed(String eventId, String type, String subscriptionId, String email, String planId, Integer amount) {}

    /**
     * Culqi envía el evento como {id, type, data} con data en texto JSON, pero en sandbox también llega el recurso
     * suelto: {"message":{"object":{subsId, planId, email}}} para la suscripción o {"object":"charge", id, email,
     * amount} para el cargo. Se aceptan todos.
     */
    Parsed parse(JsonNode event) {
        JsonNode data = event.has("data") ? event.path("data") : event;
        if (data.isString()) data = json.readTree(data.asString());
        JsonNode object = data.path("message").path("object");
        if (object.isMissingNode() || !object.isObject()) object = data.path("object").isObject() ? data.path("object") : data;
        String subscription = subscriptionId(data, object);
        String email = text(object, "email", text(data, "email", null));
        String planId = text(object, "planId", text(object, "plan_id", text(data, "plan_id", null)));
        Integer amount = null;
        if (object.path("amount").isNumber()) amount = object.path("amount").asInt();
        else if (data.path("amount").isNumber()) amount = data.path("amount").asInt();
        boolean charge = "charge".equals(text(object, "object", text(data, "object", "")));
        String type = text(event, "type", null);
        if (type == null) type = charge ? "charge.creation.succeeded" : subscription != null ? "subscription.creation.succeeded" : "";
        String id = text(event, "id", null);
        if (id == null || (event == data && !id.startsWith("evt_"))) {
            // Recurso suelto sin envoltura de evento: se identifica por el recurso para no procesarlo dos veces.
            String resource = charge ? text(object, "id", null) : subscription;
            id = resource == null ? null : type + ":" + resource;
        }
        return new Parsed(id, type, subscription, email, planId, amount);
    }

    @Transactional
    public Map<String, String> receive(String suppliedToken, String rawBody) {
        if (token.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        if (!Hmac.equalsConstantTime(token, suppliedToken)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Parsed event = parse(json.readTree(rawBody));
        String eventId = event.eventId();
        if (eventId == null || eventId.isBlank() || eventId.length() > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Outcome outcome = classify(event.type());
        String[] why = {null};
        var local = outcome == Outcome.IGNORED ? null : locate(event, why);
        if (!events.register("CULQI", eventId, local == null ? null : local.tenantId(), event.type(), rawBody))
            return Map.of("result", "duplicate");
        if (local == null || outcome == Outcome.IGNORED) {
            events.finish("CULQI", eventId, "IGNORED", local == null && outcome != Outcome.IGNORED ? why[0] : null);
            return Map.of("result", "ignored");
        }
        String subscriptionId = local.culqiSubscriptionId();
        JsonNode remote = culqi.subscription(subscriptionId);
        String remoteTenant = remote.path("metadata").path("tenant_id").asText("");
        if (!remoteTenant.isBlank() && !local.tenantId().toString().equals(remoteTenant)) {
            events.finish("CULQI", eventId, "FAILED", "metadata.tenant_id no coincide");
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        apply(local, outcome, eventId);
        events.finish("CULQI", eventId, "PROCESSED", null);
        return Map.of("result", outcome.name());
    }

    /** Suscripción local del evento: por id; si el evento no lo trae (cargos), por correo y monto o plan. */
    private Subscription locate(Parsed event, String[] why) {
        if (event.subscriptionId() != null) {
            var found = subscriptions.findByCulqiSubscription(event.subscriptionId());
            if (found.isPresent()) return found.get();
        }
        if (event.email() == null) {
            why[0] = "Suscripción desconocida" + (event.subscriptionId() == null ? " (el evento no trae subsId ni correo)" : " " + event.subscriptionId());
            return null;
        }
        List<Subscription> byEmail = subscriptions.findByCulqiEmail(event.email()).stream()
                .filter(s -> matchesPlan(s, event)).toList();
        if (byEmail.size() == 1) return byEmail.get(0);
        why[0] = byEmail.isEmpty() ? "Ninguna suscripción pendiente o activa con el correo " + event.email()
                + (event.amount() == null ? "" : " y monto " + event.amount())
                : "Más de una suscripción con el correo " + event.email() + "; no se puede saber cuál pagó";
        return null;
    }

    /** El plan (pendiente o actual) coincide con el planId o el monto del evento, si los trae. */
    private boolean matchesPlan(Subscription s, Parsed event) {
        String code = s.pendingPlanCode() != null ? s.pendingPlanCode() : s.planCode();
        var plan = plans.find(code).orElse(null);
        if (plan == null) return false;
        if (event.planId() != null && !event.planId().equals(plan.culqiPlanId())) return false;
        return event.amount() == null || event.amount().intValue() == plan.priceCents();
    }

    private static String text(JsonNode node, String field, String fallback) {
        String v = node.path(field).asText("");
        return v.isBlank() ? fallback : v;
    }

    private void apply(Subscription s, Outcome outcome, String eventId) {
        switch (outcome) {
            case ACTIVATED -> {
                String plan = s.pendingPlanCode() != null ? s.pendingPlanCode() : s.planCode();
                // Culqi avisa la misma compra dos veces (suscripción creada y cargo cobrado). Si el periodo ya está
                // cubierto por más de una semana, este aviso no es una renovación y no extiende otro mes.
                if (s.status() == SubscriptionStatus.ACTIVE && s.pendingPlanCode() == null && s.currentPeriodEnd() != null
                        && s.currentPeriodEnd().isAfter(OffsetDateTime.now().plusDays(7))) return;
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

    private static String subscriptionId(JsonNode data, JsonNode object) {
        for (JsonNode node : new JsonNode[]{object, data}) {
            for (String field : new String[]{"subsId", "subscription_id", "subscriptionId"}) {
                String v = node.path(field).asText("");
                if (!v.isBlank()) return v;
            }
            String v = node.path("subscription").path("id").asText("");
            if (!v.isBlank()) return v;
            String id = node.path("id").asText("");
            if ("subscription".equals(node.path("object").asText("")) && !id.isBlank()) return id;
            if (id.startsWith("sxn_")) return id;
        }
        return null;
    }
}
