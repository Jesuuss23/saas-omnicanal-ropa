package com.saas.omnicanal.payments;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.saas.omnicanal.payments.model.OrderPaymentStatus;
import com.saas.omnicanal.payments.service.MercadoPagoAccountService;
import com.saas.omnicanal.payments.service.MercadoPagoClient;
import com.saas.omnicanal.payments.service.MercadoPagoWebhookService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
class MercadoPagoFlowTests {
    @Autowired JdbcTemplate db;
    @Autowired MercadoPagoWebhookService webhooks;
    @Autowired ObjectMapper json;
    @MockitoBean MercadoPagoClient client;
    @MockitoBean MercadoPagoAccountService accounts;

    @Test
    void approvedPaymentClosesTheOrderOnceAndCommitsStock() throws Exception {
        UUID store = UUID.randomUUID(), product = UUID.randomUUID(), variant = UUID.randomUUID();
        UUID reservation = UUID.randomUUID(), order = UUID.randomUUID();
        db.update("INSERT INTO tenant(id,name) VALUES (?,?)", store, "Tienda MP");
        db.update("INSERT INTO product(id,tenant_id,name) VALUES (?,?,?)", product, store, "Casaca");
        db.update("""
                INSERT INTO product_variant(id,product_id,tenant_id,sku,size,color,price,stock_physical,stock_reserved)
                VALUES (?,?,?,?,?,?,80,5,2)""", variant, product, store, "CA-" + variant.toString().substring(0, 8), "L", "Negro");
        db.update("INSERT INTO cart_reservation(tenant_id,id,guest_name,guest_phone) VALUES (?,?,'Ana','51987654321')", store, reservation);
        db.update("INSERT INTO cart_reservation_line VALUES (?,?,?,2,80)", store, reservation, variant);
        db.update("""
                INSERT INTO web_checkout_order(tenant_id,id,reservation_id,guest_name,guest_phone,merchandise_total,
                    delivery_method,fulfillment_address,pickup_hours) VALUES (?,?,?,'Ana','51987654321',160,'PICKUP','Gamarra','9-18')
                """, store, order, reservation);

        when(accounts.accessToken(store)).thenReturn("APP_USR-test");
        when(client.payment(eq("APP_USR-test"), anyString())).thenReturn(json.readTree("""
                {"id":123,"status":"approved","status_detail":"accredited","transaction_amount":160.00,
                 "currency_id":"PEN","external_reference":"%s"}""".formatted(order)));

        // Ids de notificación únicos por corrida: la deduplicación es global y la base puede traer corridas previas.
        String req1 = "req-" + UUID.randomUUID(), req2 = "req-" + UUID.randomUUID();
        var result = webhooks.receive(store, "payment", "123", null, req1, "{\"type\":\"payment\"}");
        assertEquals(OrderPaymentStatus.APPROVED.name(), result.get("result"));
        assertEquals("duplicate", webhooks.receive(store, "payment", "123", null, req1, "{}").get("result"));
        webhooks.receive(store, "payment", "123", null, req2, "{}"); // nueva notificación del mismo pago

        assertEquals("PAID", db.queryForObject("SELECT status FROM web_checkout_order WHERE tenant_id=? AND id=?",
                String.class, store, order));
        assertEquals(3, db.queryForObject("SELECT stock_physical FROM product_variant WHERE id=?", Integer.class, variant));
        assertEquals(0, db.queryForObject("SELECT stock_reserved FROM product_variant WHERE id=?", Integer.class, variant));
        assertEquals(Boolean.TRUE, db.queryForObject("SELECT released FROM cart_reservation WHERE tenant_id=? AND id=?",
                Boolean.class, store, reservation));
    }

    @Test
    void amountMismatchIsFlaggedForReviewInsteadOfClosingTheOrder() throws Exception {
        UUID store = UUID.randomUUID(), product = UUID.randomUUID(), variant = UUID.randomUUID();
        UUID reservation = UUID.randomUUID(), order = UUID.randomUUID();
        db.update("INSERT INTO tenant(id,name) VALUES (?,?)", store, "Tienda MP 2");
        db.update("INSERT INTO product(id,tenant_id,name) VALUES (?,?,?)", product, store, "Polo");
        db.update("""
                INSERT INTO product_variant(id,product_id,tenant_id,sku,size,color,price,stock_physical,stock_reserved)
                VALUES (?,?,?,?,'S','Rojo',30,4,1)""", variant, product, store, "PO-" + variant.toString().substring(0, 8));
        db.update("INSERT INTO cart_reservation(tenant_id,id,guest_name,guest_phone) VALUES (?,?,'Luis','51911111111')", store, reservation);
        db.update("INSERT INTO cart_reservation_line VALUES (?,?,?,1,30)", store, reservation, variant);
        db.update("""
                INSERT INTO web_checkout_order(tenant_id,id,reservation_id,guest_name,guest_phone,merchandise_total,
                    delivery_method,fulfillment_address,pickup_hours) VALUES (?,?,?,'Luis','51911111111',30,'PICKUP','Gamarra','9-18')
                """, store, order, reservation);
        when(accounts.accessToken(store)).thenReturn("APP_USR-2");
        when(client.payment(eq("APP_USR-2"), anyString())).thenReturn(json.readTree("""
                {"id":456,"status":"approved","transaction_amount":1.00,"currency_id":"PEN","external_reference":"%s"}
                """.formatted(order)));

        webhooks.receive(store, "payment", "456", null, "req-" + UUID.randomUUID(), "{}");
        assertEquals("PENDING_PAYMENT", db.queryForObject("SELECT status FROM web_checkout_order WHERE tenant_id=? AND id=?",
                String.class, store, order));
        assertEquals(Boolean.TRUE, db.queryForObject("""
                SELECT requires_review FROM web_order_payment WHERE tenant_id=? AND provider_payment_id='456'
                """, Boolean.class, store));
    }
}
