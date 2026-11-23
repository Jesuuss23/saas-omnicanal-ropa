package com.saas.omnicanal.payments.service;

import com.saas.omnicanal.payments.model.OrderPaymentStatus;
import com.saas.omnicanal.payments.repository.OrderPaymentRepository;
import com.saas.omnicanal.shared.crypto.Hmac;
import com.saas.omnicanal.shared.webhook.PaymentWebhookEventRepository;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Notificaciones de pago de Mercado Pago para pedidos de una tienda.
 * Si MP_WEBHOOK_SECRET está configurado se valida x-signature; en todos los casos el pago se vuelve a
 * consultar a Mercado Pago con el token de la tienda (nunca se confía en el cuerpo de la notificación).
 */
@Service
public class MercadoPagoWebhookService {
    private final String secret;
    private final PaymentWebhookEventRepository events;
    private final OrderPaymentRepository payments;
    private final MercadoPagoAccountService accounts;
    private final MercadoPagoClient client;

    public MercadoPagoWebhookService(@Value("${MP_WEBHOOK_SECRET:}") String secret, PaymentWebhookEventRepository events,
                                     OrderPaymentRepository payments, MercadoPagoAccountService accounts,
                                     MercadoPagoClient client) {
        this.secret = secret;
        this.events = events;
        this.payments = payments;
        this.accounts = accounts;
        this.client = client;
    }

    /** Manifest documentado por Mercado Pago: "id:{data.id};request-id:{x-request-id};ts:{ts};". */
    static boolean validSignature(String secret, String signatureHeader, String requestId, String dataId) {
        if (signatureHeader == null || requestId == null || dataId == null) return false;
        String ts = null;
        String v1 = null;
        for (String part : signatureHeader.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) continue;
            if (kv[0].equals("ts")) ts = kv[1];
            if (kv[0].equals("v1")) v1 = kv[1];
        }
        if (ts == null || v1 == null) return false;
        String id = dataId.matches("[A-Za-z0-9]+") ? dataId.toLowerCase(Locale.ROOT) : dataId;
        String manifest = "id:" + id + ";request-id:" + requestId + ";ts:" + ts + ";";
        return Hmac.equalsConstantTime(Hmac.sha256Hex(secret, manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8)), v1);
    }

    @Transactional
    public Map<String, String> receive(UUID store, String type, String dataId, String signature, String requestId, String rawBody) {
        if (!secret.isBlank() && !validSignature(secret, signature, requestId, dataId))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (!"payment".equals(type) || dataId == null || !dataId.matches("[0-9]{1,30}")) return Map.of("result", "ignored");
        String eventId = requestId != null && !requestId.isBlank() ? requestId : "payment:" + dataId + ":" + rawBody.hashCode();
        if (!events.register("MERCADOPAGO", eventId.length() > 200 ? eventId.substring(0, 200) : eventId, store, type,
                rawBody.isBlank() ? "{}" : rawBody)) return Map.of("result", "duplicate");

        var payment = client.payment(accounts.accessToken(store), dataId);
        String reference = payment.path("external_reference").asText("");
        UUID order;
        try {
            order = UUID.fromString(reference);
        } catch (IllegalArgumentException ex) {
            events.finish("MERCADOPAGO", eventId, "IGNORED", "external_reference ajeno");
            return Map.of("result", "ignored");
        }
        var payable = payments.payable(store, order, true).orElse(null);
        if (payable == null) {
            events.finish("MERCADOPAGO", eventId, "IGNORED", "Pedido de otra tienda o inexistente");
            return Map.of("result", "ignored");
        }
        OrderPaymentStatus status = OrderPaymentStatus.fromMercadoPago(payment.path("status").asText(""));
        BigDecimal amount = payment.path("transaction_amount").decimalValue();
        String currency = payment.path("currency_id").asText("PEN");
        payments.upsertPayment(store, dataId, order, status, payment.path("status_detail").asText(null), amount, currency);

        if (status == OrderPaymentStatus.APPROVED) {
            if (!"PEN".equals(currency) || amount.compareTo(payable.amount()) != 0) {
                payments.flagReview(store, dataId, "Monto o moneda no coincide con el pedido");
            } else if (!payments.markPaid(store, order, payable.reservationId()) && !"PAID".equals(payable.status())) {
                payments.flagReview(store, dataId, "Pago aprobado sobre pedido " + payable.status());
            }
        } else if (status == OrderPaymentStatus.REFUNDED || status == OrderPaymentStatus.CHARGED_BACK) {
            payments.flagReview(store, dataId, "Pago revertido: revisar pedido y stock");
        }
        events.finish("MERCADOPAGO", eventId, "PROCESSED", null);
        return Map.of("result", status.name());
    }
}
