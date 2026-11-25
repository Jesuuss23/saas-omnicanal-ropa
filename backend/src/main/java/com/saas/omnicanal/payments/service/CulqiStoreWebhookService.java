package com.saas.omnicanal.payments.service;

import com.saas.omnicanal.payments.model.OrderPaymentStatus;
import com.saas.omnicanal.payments.repository.OrderPaymentRepository;
import com.saas.omnicanal.shared.webhook.PaymentWebhookEventRepository;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Webhook de respaldo de la cuenta Culqi de una tienda ({@code /api/webhooks/culqi/stores/{id}}). Es idempotente por
 * evento y nunca confía en el cuerpo: vuelve a consultar el cargo con la llave secreta de la tienda.
 * Cubre el caso en que el cobro se aprobó pero el navegador se cerró antes de recibir la respuesta.
 */
@Service
public class CulqiStoreWebhookService {
    static final String PROVIDER = "CULQI";

    private final PaymentWebhookEventRepository events;
    private final OrderPaymentRepository payments;
    private final CulqiAccountService accounts;
    private final StoreCulqiClient culqi;
    private final ObjectMapper json;

    public CulqiStoreWebhookService(PaymentWebhookEventRepository events, OrderPaymentRepository payments,
                                    CulqiAccountService accounts, StoreCulqiClient culqi, ObjectMapper json) {
        this.events = events;
        this.payments = payments;
        this.accounts = accounts;
        this.culqi = culqi;
        this.json = json;
    }

    @Transactional
    public Map<String, String> receive(UUID store, String rawBody) {
        JsonNode event;
        try {
            event = json.readTree(rawBody == null || rawBody.isBlank() ? "{}" : rawBody);
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        String type = event.path("type").asText("");
        // Culqi envía "data" como texto JSON; algunos reenvíos lo traen como objeto.
        JsonNode data = event.path("data");
        if (data.isString()) {
            try { data = json.readTree(data.asText()); } catch (RuntimeException ex) { data = json.createObjectNode(); }
        }
        boolean refund = type.startsWith("refund.");
        if (!type.startsWith("charge.") && !refund) return Map.of("result", "ignored");
        String chargeId = refund ? data.path("charge_id").asText("") : data.path("id").asText("");
        if (!chargeId.matches("chr_(test|live)_[A-Za-z0-9]{4,90}")) return Map.of("result", "ignored");

        String eventId = event.path("id").asText("");
        if (eventId.isBlank()) eventId = type + ":" + (refund ? data.path("id").asText(chargeId) : chargeId);
        // Prefijo por tienda: los ids de evento de Culqi son por comercio y la bandeja la comparte la suscripción del SaaS.
        eventId = "store:" + store + ":" + eventId;
        if (eventId.length() > 200) eventId = eventId.substring(0, 200);
        if (!events.register(PROVIDER, eventId, store, type, event.toString())) return Map.of("result", "duplicate");

        var account = accounts.active(store).orElse(null);
        if (account == null) {
            events.finish(PROVIDER, eventId, "IGNORED", "La tienda no tiene Culqi activo");
            return Map.of("result", "ignored");
        }
        JsonNode charge = culqi.getCharge(account.secretKey(), chargeId);
        JsonNode metadata = charge.path("metadata");
        UUID order = null;
        try {
            if (store.toString().equals(metadata.path("tenant_id").asText(""))) order = UUID.fromString(metadata.path("order_id").asText(""));
        } catch (IllegalArgumentException ignored) {
            // metadata ajena: se intenta con el registro local del cargo
        }
        if (order == null) order = payments.orderOfCharge(store, chargeId).orElse(null);
        var payable = order == null ? null : payments.payable(store, order, true).orElse(null);
        if (payable == null) {
            events.finish(PROVIDER, eventId, "IGNORED", "Cargo sin pedido de esta tienda");
            return Map.of("result", "ignored");
        }
        BigDecimal amount = BigDecimal.valueOf(charge.path("amount").asLong(0)).movePointLeft(2);
        String currency = charge.path("currency_code").asText("PEN");
        String outcome = charge.path("outcome").path("type").asText("");
        OrderPaymentStatus status = refund ? OrderPaymentStatus.REFUNDED : OrderPaymentStatus.fromCulqi(outcome);
        payments.upsertPayment(store, chargeId, order, status, refund ? type : outcome, amount, currency);

        if (status == OrderPaymentStatus.APPROVED) {
            if (!"PEN".equals(currency) || amount.compareTo(payable.amount()) != 0) {
                payments.flagReview(store, chargeId, "Monto o moneda no coincide con el pedido");
            } else if (!payments.markPaid(store, order, payable.reservationId()) && !"PAID".equals(payable.status())) {
                payments.flagReview(store, chargeId, "Pago aprobado sobre pedido " + payable.status());
            }
        } else if (status == OrderPaymentStatus.REFUNDED) {
            payments.flagReview(store, chargeId, "Reembolso en Culqi: revisar pedido y stock");
        }
        events.finish(PROVIDER, eventId, "PROCESSED", null);
        return Map.of("result", status.name());
    }
}
