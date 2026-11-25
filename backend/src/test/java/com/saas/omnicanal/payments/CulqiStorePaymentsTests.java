package com.saas.omnicanal.payments;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.saas.omnicanal.checkout.service.OrderTrackingTokens;
import com.saas.omnicanal.payments.service.CulqiAccountService;
import com.saas.omnicanal.payments.service.CulqiChargeService;
import com.saas.omnicanal.payments.service.CulqiStoreWebhookService;
import com.saas.omnicanal.payments.service.StoreCulqiClient;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Cobros de cada tienda con su propia cuenta Culqi: conexión, cobro con 3DS, rechazo y webhook de respaldo. */
@SpringBootTest
class CulqiStorePaymentsTests {
    static final String PK = "pk_test_abcdEFGH1234", SK = "sk_test_secretXYZ98765";

    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired CulqiAccountService accounts;
    @Autowired CulqiChargeService charges;
    @Autowired CulqiStoreWebhookService webhooks;
    @MockitoBean StoreCulqiClient culqi;

    @Test
    void connectValidatesKeysStoresTheSecretEncryptedAndDisconnects() {
        UUID store = store();
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> accounts.connect(store, "pk_x", SK)).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> accounts.connect(store, "pk_live_abcdEFGH1234", SK)).getStatusCode().value(), "modos distintos");
        when(culqi.validSecretKey("sk_test_malaLlave0000")).thenReturn(false);
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> accounts.connect(store, PK, "sk_test_malaLlave0000")).getStatusCode().value());
        assertFalse(accounts.publicConfig(store).enabled());

        when(culqi.validSecretKey(SK)).thenReturn(true);
        var account = accounts.connect(store, " " + PK + " ", SK);
        assertTrue(account.linked());
        assertFalse(account.liveMode());
        assertEquals("sk_test_••••8765", account.secretKeyHint());
        byte[] stored = db.queryForObject("SELECT access_token_ciphertext FROM store_payment_account WHERE tenant_id=?", byte[].class, store);
        assertFalse(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1).contains(SK), "la llave secreta no se guarda en claro");
        assertEquals("CULQI", db.queryForObject("SELECT provider FROM store_payment_account WHERE tenant_id=?", String.class, store));
        var config = accounts.publicConfig(store);
        assertTrue(config.enabled());
        assertEquals(PK, config.publicKey());

        accounts.disconnect(store);
        assertFalse(accounts.publicConfig(store).enabled());
    }

    @Test
    void approvedChargeGoesToTheStoreAccountAndPaysTheOrderAtOnce() throws Exception {
        UUID store = connectedStore();
        var o = order(store, 159.90);
        when(culqi.charge(eq(SK), anyMap())).thenReturn(new StoreCulqiClient.ChargeResult(201, json.readTree("""
                {"id":"chr_test_ok12345","amount":15990,"currency_code":"PEN","outcome":{"type":"venta_exitosa","user_message":"Su compra ha sido exitosa."}}
                """)));

        var result = charges.pay(store, o.order, o.token, new CulqiChargeService.PayRequest("tkn_test_card1234", "Ana@Mail.com", null));
        assertEquals("PAID", result.status());
        assertEquals("PAID", status(store, o.order));
        assertEquals(3, db.queryForObject("SELECT stock_physical FROM product_variant WHERE id=?", Integer.class, o.variant));

        @SuppressWarnings("unchecked")
        var body = (org.mockito.ArgumentCaptor<Map<String, Object>>) (Object) org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(culqi).charge(eq(SK), body.capture());
        assertEquals(15990, body.getValue().get("amount"));
        assertEquals("PEN", body.getValue().get("currency_code"));
        assertEquals("ana@mail.com", body.getValue().get("email"));
        assertEquals("tkn_test_card1234", body.getValue().get("source_id"));
        assertEquals(Map.of("tenant_id", store.toString(), "order_id", o.order.toString()), body.getValue().get("metadata"));

        // Un segundo intento sobre el pedido ya pagado no vuelve a cobrar.
        assertEquals("PAID", charges.pay(store, o.order, o.token, new CulqiChargeService.PayRequest("tkn_test_card1234", "ana@mail.com", null)).status());
        verify(culqi, times(1)).charge(anyString(), anyMap());
    }

    @Test
    void threeDsReviewDeclineAndWrongTokenKeepTheOrderPending() throws Exception {
        UUID store = connectedStore();
        var o = order(store, 30);
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> charges.pay(store, o.order, "otro",
                new CulqiChargeService.PayRequest("tkn_test_card1234", "a@b.pe", null))).getStatusCode().value());

        when(culqi.charge(eq(SK), argThat(m -> m != null && !m.containsKey("authentication_3DS"))))
                .thenReturn(new StoreCulqiClient.ChargeResult(200, json.readTree("{\"action_code\":\"REVIEW\",\"user_message\":\"Valida con tu banco\"}")));
        var review = charges.pay(store, o.order, o.token, new CulqiChargeService.PayRequest("tkn_test_card1234", "a@b.pe", null));
        assertEquals("REQUIRES_3DS", review.status());
        assertEquals("PENDING_PAYMENT", status(store, o.order));

        when(culqi.charge(eq(SK), argThat(m -> m != null && m.containsKey("authentication_3DS"))))
                .thenReturn(new StoreCulqiClient.ChargeResult(402, json.readTree("{\"object\":\"error\",\"user_message\":\"Tu tarjeta no tiene fondos suficientes.\"}")));
        var declined = charges.pay(store, o.order, o.token, new CulqiChargeService.PayRequest("tkn_test_card1234", "a@b.pe",
                new CulqiChargeService.Authentication3DS("05", "xid", "cavv", "2.1.0", "ds-1")));
        assertEquals("DECLINED", declined.status());
        assertEquals("Tu tarjeta no tiene fondos suficientes.", declined.message());
        assertEquals("PENDING_PAYMENT", status(store, o.order));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM web_order_payment WHERE tenant_id=?", Integer.class, store));
    }

    @Test
    void webhookVerifiesTheChargeWithCulqiIsIdempotentAndFlagsRefunds() throws Exception {
        UUID store = connectedStore();
        var o = order(store, 80);
        String charge = "chr_test_web" + UUID.randomUUID().toString().substring(0, 8);
        when(culqi.getCharge(SK, charge)).thenReturn(json.readTree("""
                {"id":"%s","amount":8000,"currency_code":"PEN","outcome":{"type":"venta_exitosa"},
                 "metadata":{"tenant_id":"%s","order_id":"%s"}}""".formatted(charge, store, o.order)));
        String evt = "evt_test_" + UUID.randomUUID();
        // El cuerpo dice cualquier cosa sobre el monto: solo cuenta lo que Culqi devuelve al consultar el cargo.
        String body = """
                {"object":"event","id":"%s","type":"charge.creation.succeeded","data":"{\\"id\\":\\"%s\\",\\"amount\\":1}"}
                """.formatted(evt, charge);

        assertEquals("APPROVED", webhooks.receive(store, body).get("result"));
        assertEquals("duplicate", webhooks.receive(store, body).get("result"));
        assertEquals("PAID", status(store, o.order));
        verify(culqi, times(1)).getCharge(SK, charge);

        String refund = """
                {"object":"event","id":"evt_test_%s","type":"refund.creation.succeeded","data":{"id":"ref_test_1","charge_id":"%s","amount":8000}}
                """.formatted(UUID.randomUUID(), charge);
        assertEquals("REFUNDED", webhooks.receive(store, refund).get("result"));
        assertEquals(Boolean.TRUE, db.queryForObject("SELECT requires_review FROM web_order_payment WHERE tenant_id=? AND provider_payment_id=?",
                Boolean.class, store, charge));
        assertEquals("ignored", webhooks.receive(store, "{\"type\":\"customer.creation.succeeded\",\"data\":{}}").get("result"));
    }

    @Test
    void centsAreExact() {
        assertEquals(15990, CulqiChargeService.toCents(new BigDecimal("159.9")));
        assertEquals(100, CulqiChargeService.toCents(new BigDecimal("1.00")));
    }

    record Order(UUID order, UUID variant, String token) {}

    private UUID store() {
        UUID store = UUID.randomUUID();
        db.update("INSERT INTO tenant(id,name) VALUES (?, 'Tienda Culqi')", store);
        db.update("INSERT INTO tenant_subscription(tenant_id,plan_code,status,trial_ends_at) VALUES (?, 'TRIAL', 'TRIAL', now() + interval '5 days')", store);
        return store;
    }

    private UUID connectedStore() {
        UUID store = store();
        when(culqi.validSecretKey(SK)).thenReturn(true);
        accounts.connect(store, PK, SK);
        return store;
    }

    private Order order(UUID store, double total) {
        UUID product = UUID.randomUUID(), variant = UUID.randomUUID(), reservation = UUID.randomUUID(), order = UUID.randomUUID();
        db.update("INSERT INTO product(id,tenant_id,name) VALUES (?,?,?)", product, store, "Casaca");
        db.update("""
                INSERT INTO product_variant(id,product_id,tenant_id,sku,size,color,price,stock_physical,stock_reserved)
                VALUES (?,?,?,?,'L','Negro',?,5,2)""", variant, product, store, "CA-" + variant.toString().substring(0, 8), total / 2);
        db.update("INSERT INTO cart_reservation(tenant_id,id,guest_name,guest_phone,expires_at) VALUES (?,?,'Ana','51987654321',now()+interval '10 minutes')", store, reservation);
        db.update("INSERT INTO cart_reservation_line VALUES (?,?,?,2,?)", store, reservation, variant, total / 2);
        db.update("""
                INSERT INTO web_checkout_order(tenant_id,id,reservation_id,guest_name,guest_phone,merchandise_total,
                    delivery_method,fulfillment_address,pickup_hours) VALUES (?,?,?,'Ana','51987654321',?,'PICKUP','Gamarra','9-18')
                """, store, order, reservation, total);
        return new Order(order, variant, OrderTrackingTokens.token(store, order, reservation));
    }

    private String status(UUID store, UUID order) {
        return db.queryForObject("SELECT status FROM web_checkout_order WHERE tenant_id=? AND id=?", String.class, store, order);
    }
}
