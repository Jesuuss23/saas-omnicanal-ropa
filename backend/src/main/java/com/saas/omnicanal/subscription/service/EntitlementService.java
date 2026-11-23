package com.saas.omnicanal.subscription.service;

import com.saas.omnicanal.subscription.model.Entitlement;
import com.saas.omnicanal.subscription.model.Subscription;
import com.saas.omnicanal.subscription.repository.PlanRepository;
import com.saas.omnicanal.subscription.repository.SubscriptionRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Punto único para preguntar "¿esta tienda puede hacer X?". Se evalúa siempre en el servidor.
 * Tienda suspendida, cancelada o con trial vencido: solo rigen los overrides.
 */
@Service
public class EntitlementService {
    private final PlanRepository plans;
    private final SubscriptionRepository subscriptions;

    public EntitlementService(PlanRepository plans, SubscriptionRepository subscriptions) {
        this.plans = plans;
        this.subscriptions = subscriptions;
    }

    public Map<String, Entitlement> effective(UUID tenant) {
        String plan = subscriptions.find(tenant)
                .filter(s -> s.status().grantsPlan())
                .map(Subscription::planCode)
                .orElse(null);
        return plans.effective(tenant, plan);
    }

    public Entitlement get(UUID tenant, String key) {
        return effective(tenant).getOrDefault(key, new Entitlement(key, false, 0L));
    }

    public boolean enabled(UUID tenant, String key) {
        return get(tenant, key).enabled();
    }

    public void requireEnabled(UUID tenant, String key) {
        if (!enabled(tenant, key))
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Tu plan no incluye esta función (" + key + ")");
    }

    /** Lanza 402 si sumar {@code adding} al uso actual excede la cuota del plan. */
    public void requireQuota(UUID tenant, String key, long currentUsage, long adding) {
        Entitlement e = get(tenant, key);
        if (!e.allows(currentUsage + adding))
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "Alcanzaste el límite de tu plan para " + key + (e.limit() == null ? "" : " (" + e.limit() + ")"));
    }
}
