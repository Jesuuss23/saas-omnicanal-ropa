package com.saas.omnicanal.payments.service;

import com.saas.omnicanal.checkout.service.OrderTrackingTokens;
import com.saas.omnicanal.payments.model.PayableOrder;
import com.saas.omnicanal.payments.repository.OrderPaymentRepository;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/** Crea la preferencia de Checkout Pro con la cuenta de la tienda para un pedido web de invitado. */
@Service
public class MercadoPagoCheckoutService {
    private final OrderPaymentRepository payments;
    private final MercadoPagoAccountService accounts;
    private final MercadoPagoClient client;
    private final String publicApiBase;
    private final String storefrontBase;

    public MercadoPagoCheckoutService(OrderPaymentRepository payments, MercadoPagoAccountService accounts,
                                      MercadoPagoClient client,
                                      @Value("${PUBLIC_API_BASE_URL:}") String publicApiBase,
                                      @Value("${STOREFRONT_BASE_URL:}") String storefrontBase) {
        this.payments = payments;
        this.accounts = accounts;
        this.client = client;
        this.publicApiBase = publicApiBase.replaceAll("/+$", "");
        this.storefrontBase = storefrontBase.replaceAll("/+$", "");
    }

    public record Checkout(String preferenceId, String checkoutUrl) {}

    @Transactional
    public Checkout start(UUID store, UUID order, String trackingToken) {
        PayableOrder o = payments.payable(store, order, true)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!OrderTrackingTokens.matches(store, order, o.reservationId(), trackingToken))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!"PENDING_PAYMENT".equals(o.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "El pedido ya no admite pagos");
        if (!o.reservationActive()) throw new ResponseStatusException(HttpStatus.GONE, "La reserva venció");
        if (!o.amountKnown()) throw new ResponseStatusException(HttpStatus.CONFLICT, "La tienda aún debe confirmar el costo de delivery");
        var existing = payments.intent(store, order);
        if (existing.isPresent() && existing.get().amount().compareTo(o.amount()) == 0)
            return new Checkout(existing.get().preferenceId(), existing.get().checkoutUrl());

        Map<String, Object> preference = new LinkedHashMap<>();
        preference.put("items", List.of(Map.of("id", order.toString(), "title", "Pedido en " + o.storeName(),
                "quantity", 1, "currency_id", "PEN", "unit_price", o.amount())));
        preference.put("external_reference", order.toString());
        preference.put("metadata", Map.of("tenant_id", store.toString(), "order_id", order.toString()));
        if (!publicApiBase.isBlank()) preference.put("notification_url", publicApiBase + "/api/webhooks/mercadopago/" + store);
        if (!storefrontBase.isBlank()) {
            String back = storefrontBase + "/tienda/" + store + "/pedido/" + order + "#" + trackingToken;
            preference.put("back_urls", Map.of("success", back, "pending", back, "failure", back));
            preference.put("auto_return", "approved");
        }
        preference.put("expires", true);
        preference.put("expiration_date_to", o.reservationExpiresAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        preference.put("binary_mode", true);

        JsonNode created = client.createPreference(accounts.accessToken(store), preference,
                "order-" + order + "-" + o.amount().toPlainString());
        String id = created.path("id").asText("");
        String url = created.path("init_point").asText("");
        if (id.isBlank() || url.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        payments.saveIntent(store, order, id, url, o.amount());
        return new Checkout(id, url);
    }
}
