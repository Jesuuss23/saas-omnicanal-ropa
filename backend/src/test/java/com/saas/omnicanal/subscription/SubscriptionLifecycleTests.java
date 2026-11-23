package com.saas.omnicanal.subscription;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import com.saas.omnicanal.catalog.model.VariantDraft;
import com.saas.omnicanal.catalog.service.ProductCatalogService;
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
}
