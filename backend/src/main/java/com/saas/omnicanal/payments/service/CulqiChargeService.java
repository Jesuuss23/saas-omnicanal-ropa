package com.saas.omnicanal.payments.service;

import com.saas.omnicanal.checkout.service.OrderTrackingTokens;
import com.saas.omnicanal.payments.model.OrderPaymentStatus;
import com.saas.omnicanal.payments.model.PayableOrder;
import com.saas.omnicanal.payments.repository.OrderPaymentRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/**
 * Cobro de un pedido web con Culqi: el token de tarjeta (Culqi.js) se cobra con la llave secreta de la tienda, así el
 * dinero llega directo a su cuenta. Con {@code venta_exitosa} el pedido queda PAID en la misma transacción.
 * Si Culqi pide 3‑D Secure ({@code action_code = REVIEW}) se devuelve REQUIRES_3DS y el navegador reintenta con la
 * autenticación de Culqi3DS.
 */
@Service
public class CulqiChargeService {
    private static final Logger log = LoggerFactory.getLogger(CulqiChargeService.class);
    static final Pattern TOKEN = Pattern.compile("^tkn_(test|live)_[A-Za-z0-9]{8,90}$");
    static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,190}\\.[^@\\s]{2,}$");

    private final OrderPaymentRepository payments;
    private final CulqiAccountService accounts;
    private final StoreCulqiClient culqi;

    public CulqiChargeService(OrderPaymentRepository payments, CulqiAccountService accounts, StoreCulqiClient culqi) {
        this.payments = payments;
        this.accounts = accounts;
        this.culqi = culqi;
    }

    /** Datos de 3‑D Secure que devuelve Culqi3DS en el navegador; se reenvían tal cual a Culqi. */
    public record Authentication3DS(String eci, String xid, String cavv, String protocolVersion,
                                    String directoryServerTransactionId) {}

    public record PayRequest(String tokenId, String email, Authentication3DS authentication3DS) {}

    /** status: PAID, REQUIRES_3DS o DECLINED; {@code message} es el texto de Culqi para el comprador. */
    public record PayResult(String status, String chargeId, String message, BigDecimal amount) {}

    /** El monto de Culqi es un entero en céntimos: S/ 159.90 → 15990. */
    public static int toCents(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).intValueExact();
    }

    @Transactional
    public PayResult pay(UUID store, UUID order, String trackingToken, PayRequest req) {
        String token = req.tokenId() == null ? "" : req.tokenId().strip();
        String email = req.email() == null ? "" : req.email().strip().toLowerCase(java.util.Locale.ROOT);
        if (!TOKEN.matcher(token).matches()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token de tarjeta inválido");
        if (!EMAIL.matcher(email).matches()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe un email válido");

        PayableOrder o = payments.payable(store, order, true).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!OrderTrackingTokens.matches(store, order, o.reservationId(), trackingToken))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if ("PAID".equals(o.status())) return new PayResult("PAID", null, "El pedido ya está pagado.", o.amount());
        if (!"PENDING_PAYMENT".equals(o.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "El pedido ya no admite pagos");
        if (!o.reservationActive()) throw new ResponseStatusException(HttpStatus.GONE, "La reserva venció");
        if (!o.amountKnown()) throw new ResponseStatusException(HttpStatus.CONFLICT, "La tienda aún debe confirmar el costo de delivery");
        var account = accounts.active(store)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "La tienda no tiene cobros en línea activos"));
        boolean liveToken = token.startsWith("tkn_live_");
        if (liveToken != account.liveMode())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El token no corresponde al modo de la tienda (prueba o producción)");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", toCents(o.amount()));
        body.put("currency_code", "PEN");
        body.put("email", email);
        body.put("source_id", token);
        body.put("description", ("Pedido en " + o.storeName()).substring(0, Math.min(80, ("Pedido en " + o.storeName()).length())));
        body.put("metadata", Map.of("tenant_id", store.toString(), "order_id", order.toString()));
        if (req.authentication3DS() != null) {
            var a = req.authentication3DS();
            Map<String, Object> auth = new LinkedHashMap<>();
            if (a.eci() != null) auth.put("eci", a.eci());
            if (a.xid() != null) auth.put("xid", a.xid());
            if (a.cavv() != null) auth.put("cavv", a.cavv());
            if (a.protocolVersion() != null) auth.put("protocolVersion", a.protocolVersion());
            if (a.directoryServerTransactionId() != null) auth.put("directoryServerTransactionId", a.directoryServerTransactionId());
            body.put("authentication_3DS", auth);
        }

        var result = culqi.charge(account.secretKey(), body);
        JsonNode res = result.body();
        if (result.status() == 401) {
            accounts.markError(store, "Culqi rechazó la llave secreta al cobrar");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "La tienda debe revisar su conexión con Culqi");
        }
        if ("REVIEW".equalsIgnoreCase(res.path("action_code").asText(""))) {
            return new PayResult("REQUIRES_3DS", null, res.path("user_message").asText("Tu banco pide confirmar la compra."), o.amount());
        }
        if (result.status() != 201 && result.status() != 200) {
            String message = res.path("user_message").asText(res.path("merchant_message").asText("La tarjeta fue rechazada."));
            log.info("Culqi rechazó el cobro del pedido {} de {}: HTTP {} {}", order, store, result.status(), res.path("code").asText(""));
            return new PayResult("DECLINED", null, message, o.amount());
        }
        String chargeId = res.path("id").asText("");
        OrderPaymentStatus status = OrderPaymentStatus.fromCulqi(res.path("outcome").path("type").asText(""));
        if (chargeId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Culqi no devolvió el cargo");
        BigDecimal charged = BigDecimal.valueOf(res.path("amount").asLong(toCents(o.amount()))).movePointLeft(2);
        String currency = res.path("currency_code").asText("PEN");
        payments.upsertPayment(store, chargeId, order, status, res.path("outcome").path("type").asText(null), charged, currency);
        if (status != OrderPaymentStatus.APPROVED) {
            return new PayResult("DECLINED", chargeId, res.path("outcome").path("user_message").asText("La tarjeta fue rechazada."), o.amount());
        }
        if (!"PEN".equals(currency) || charged.compareTo(o.amount()) != 0) {
            payments.flagReview(store, chargeId, "Monto o moneda del cargo no coincide con el pedido");
        } else {
            payments.markPaid(store, order, o.reservationId());
        }
        return new PayResult("PAID", chargeId, res.path("outcome").path("user_message").asText("Pago aprobado."), o.amount());
    }
}
