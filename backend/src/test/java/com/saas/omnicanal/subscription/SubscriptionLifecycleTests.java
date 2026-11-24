package com.saas.omnicanal.subscription;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.saas.omnicanal.catalog.model.VariantDraft;
import com.saas.omnicanal.catalog.service.ProductCatalogService;
import com.saas.omnicanal.platformadmin.model.SubscriptionChange;
import com.saas.omnicanal.platformadmin.service.AdminConsoleService;
import com.saas.omnicanal.subscription.model.Entitlements;
import com.saas.omnicanal.subscription.service.CulqiClient;
import com.saas.omnicanal.subscription.service.CulqiWebhookService;
import com.saas.omnicanal.subscription.service.EntitlementService;
import com.saas.omnicanal.subscription.service.SubscriptionService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
class SubscriptionLifecycleTests {
    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry props) {
        props.add("CULQI_WEBHOOK_TOKEN", () -> "culqi-hook-token");
    }

    @Autowired JdbcTemplate db;
    @Autowired SubscriptionService subscriptions;
    @Autowired EntitlementService entitlements;
    @Autowired ProductCatalogService catalog;
    @Autowired CulqiWebhookService culqiWebhooks;
    @Autowired ObjectMapper json;
    @Autowired AdminConsoleService console;
    @MockitoBean CulqiClient culqi;

    UUID newTrialStore() {
        UUID store = UUID.randomUUID();
        db.update("INSERT INTO tenant(id,name) VALUES (?,?)", store, "Tienda trial");
        subscriptions.startTrial(store);
        return store;
    }

    @Test
    void trialQuotaIsEnforcedOnTheServer() {
        UUID store = newTrialStore();
        assertFalse(entitlements.enabled(store, Entitlements.FISCAL_PRODUCTION), "el trial solo factura en Beta");
        for (int i = 0; i < 20; i++) {
            catalog.create(store, "Prenda " + i, null,
                    List.of(new VariantDraft("SKU-" + i + "-" + store.toString().substring(0, 6), "M", "Azul", BigDecimal.TEN, 1)));
        }
        var ex = assertThrows(ResponseStatusException.class, () -> catalog.create(store, "Prenda 21", null,
                List.of(new VariantDraft("SKU-X-" + store.toString().substring(0, 6), "M", "Azul", BigDecimal.TEN, 1))));
        assertEquals(402, ex.getStatusCode().value());
    }

    @Test
    void culqiWebhookActivatesThePlanIdempotently() throws Exception {
        UUID store = newTrialStore();
        String culqiSubscription = "sxn_" + store.toString().replace("-", "").substring(0, 12);
        db.update("""
                UPDATE tenant_subscription SET status='PENDING_PAYMENT', pending_plan_code='BASICO', culqi_subscription_id=?
                 WHERE tenant_id=?""", culqiSubscription, store);
        when(culqi.subscription(culqiSubscription)).thenReturn(json.readTree(
                "{\"id\":\"%s\",\"metadata\":{\"tenant_id\":\"%s\"}}".formatted(culqiSubscription, store)));
        String event = """
                {"object":"event","id":"evt_%s","type":"subscription.charge.succeeded",
                 "data":"{\\"object\\":\\"subscription\\",\\"id\\":\\"%s\\"}"}
                """.formatted(store, culqiSubscription);

        assertThrows(ResponseStatusException.class, () -> culqiWebhooks.receive("token-incorrecto", event));
        assertEquals("ACTIVATED", culqiWebhooks.receive("culqi-hook-token", event).get("result"));
        assertEquals("duplicate", culqiWebhooks.receive("culqi-hook-token", event).get("result"));

        var overview = subscriptions.overview(store);
        assertEquals("ACTIVE", overview.status());
        assertEquals("BASICO", overview.planCode());
        assertNotNull(overview.currentPeriodEnd());
        assertEquals(100L, entitlements.get(store, Entitlements.CATALOG_PRODUCTS).limit());
        assertTrue(entitlements.enabled(store, Entitlements.FISCAL_PRODUCTION));
    }

    @Test
    void webhookForASubscriptionOfAnotherTenantIsRejected() throws Exception {
        UUID store = newTrialStore();
        String culqiSubscription = "sxn_" + store.toString().replace("-", "").substring(0, 14);
        db.update("""
                UPDATE tenant_subscription SET status='PENDING_PAYMENT', pending_plan_code='PRO', culqi_subscription_id=?
                 WHERE tenant_id=?""", culqiSubscription, store);
        when(culqi.subscription(culqiSubscription)).thenReturn(json.readTree(
                "{\"id\":\"%s\",\"metadata\":{\"tenant_id\":\"%s\"}}".formatted(culqiSubscription, UUID.randomUUID())));
        String event = "{\"id\":\"evt_x_%s\",\"type\":\"subscription.creation.succeeded\",\"data\":{\"subscription_id\":\"%s\"}}"
                .formatted(store, culqiSubscription);
        assertThrows(ResponseStatusException.class, () -> culqiWebhooks.receive("culqi-hook-token", event));
        assertEquals("PENDING_PAYMENT", subscriptions.overview(store).status());
    }

    /**
     * Caso real: el checkout quedó en PENDING_PAYMENT porque el webhook no estaba listo, el superadmin lo pasó a
     * prueba y luego lo canceló. El intento viejo se cancela en Culqi y se limpia, y un checkout nuevo funciona
     * en vez de chocar con "Ya te encuentras registrado a este plan".
     */
    @Test
    void manualAdjustmentDiscardsTheStuckCulqiAttemptAndAllowsANewCheckout() throws Exception {
        UUID store = newTrialStore();
        db.update("UPDATE saas_plan SET culqi_plan_id=coalesce(culqi_plan_id,'pln_test_basico') WHERE code='BASICO'");
        String stuck = "sxn_old" + store.toString().replace("-", "").substring(0, 10);
        db.update("""
                UPDATE tenant_subscription SET status='PENDING_PAYMENT', pending_plan_code='BASICO', culqi_subscription_id=?,
                       culqi_customer_id='cus_test', updated_at=now() WHERE tenant_id=?""", stuck, store);
        when(culqi.cancelSubscription(stuck)).thenReturn(true);
        java.security.Principal admin = () -> "admin@e2e.test";
        String fresh = "sxn_new" + store.toString().replace("-", "").substring(0, 10);
        String retry = "sxn_rty" + store.toString().replace("-", "").substring(0, 10);

        console.changeSubscription(admin, store, new SubscriptionChange(SubscriptionChange.Action.EXTEND_TRIAL, null, 7, null, "Webhook no estaba listo"));
        verify(culqi).cancelSubscription(stuck);
        var row = db.queryForMap("SELECT status, culqi_subscription_id, pending_plan_code, culqi_customer_id FROM tenant_subscription WHERE tenant_id=?", store);
        assertEquals("TRIAL", row.get("status"));
        assertNull(row.get("culqi_subscription_id"));
        assertNull(row.get("pending_plan_code"));
        assertEquals("cus_test", row.get("culqi_customer_id"), "el cliente de Culqi se reutiliza");
        console.changeSubscription(admin, store, new SubscriptionChange(SubscriptionChange.Action.CANCEL, null, null, null, "Prueba"));

        // Checkout nuevo: crea otra suscripción en Culqi y queda pendiente de ese nuevo id.
        when(culqi.createCard("cus_test", "tkn_nuevo123")).thenReturn("crd_test");
        when(culqi.createSubscription(eq("crd_test"), anyString(), anyMap())).thenReturn(json.readTree("{\"id\":\"" + fresh + "\"}"));
        var overview = subscriptions.checkout(store, new SubscriptionService.CheckoutInput("BASICO", "tkn_nuevo123", "a@b.pe", "Ana", "Paz", "987654321", "Av. Lima 123", "Lima"));
        assertEquals("PENDING_PAYMENT", overview.status());
        assertEquals(fresh, db.queryForObject("SELECT culqi_subscription_id FROM tenant_subscription WHERE tenant_id=?", String.class, store));

        // Reintento inmediato: idempotente. Si el pago lleva más de 15 minutos sin confirmarse, se puede reintentar.
        subscriptions.checkout(store, new SubscriptionService.CheckoutInput("BASICO", "tkn_nuevo123", "a@b.pe", "Ana", "Paz", "987654321", "Av. Lima 123", "Lima"));
        verify(culqi, times(1)).createSubscription(any(), any(), any());
        db.update("UPDATE tenant_subscription SET updated_at=now() - interval '20 minutes' WHERE tenant_id=?", store);
        when(culqi.cancelSubscription(fresh)).thenReturn(true);
        when(culqi.createSubscription(eq("crd_test"), anyString(), anyMap())).thenReturn(json.readTree("{\"id\":\"" + retry + "\"}"));
        subscriptions.checkout(store, new SubscriptionService.CheckoutInput("BASICO", "tkn_nuevo123", "a@b.pe", "Ana", "Paz", "987654321", "Av. Lima 123", "Lima"));
        verify(culqi).cancelSubscription(fresh);
        assertEquals(retry, db.queryForObject("SELECT culqi_subscription_id FROM tenant_subscription WHERE tenant_id=?", String.class, store));
    }
}
