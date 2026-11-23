package com.saas.omnicanal.subscription.service;

import com.saas.omnicanal.subscription.model.Entitlement;
import com.saas.omnicanal.subscription.model.Plan;
import com.saas.omnicanal.subscription.model.Subscription;
import com.saas.omnicanal.subscription.model.SubscriptionStatus;
import com.saas.omnicanal.subscription.repository.PlanRepository;
import com.saas.omnicanal.subscription.repository.SubscriptionRepository;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ciclo de vida de la suscripción de la tienda. El checkout solo deja la suscripción en PENDING_PAYMENT:
 * la activación ocurre exclusivamente al procesar el webhook de Culqi ({@link CulqiWebhookService}).
 */
@Service
public class SubscriptionService {
    private final SubscriptionRepository subscriptions;
    private final PlanRepository plans;
    private final EntitlementService entitlements;
    private final CulqiClient culqi;
    private final int trialDays;

    public SubscriptionService(SubscriptionRepository subscriptions, PlanRepository plans, EntitlementService entitlements,
                               CulqiClient culqi, @Value("${subscription.trial-days:14}") int trialDays) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.entitlements = entitlements;
        this.culqi = culqi;
        this.trialDays = trialDays;
    }

    @Transactional
    public void startTrial(UUID tenant) {
        subscriptions.startTrial(tenant, trialDays);
        subscriptions.logEvent(tenant, null, SubscriptionStatus.TRIAL, "TRIAL", "REGISTRATION", null);
    }

    public record Overview(String status, String planCode, String pendingPlanCode, OffsetDateTime trialEndsAt,
                           OffsetDateTime currentPeriodEnd, OffsetDateTime graceEndsAt,
                           Collection<Entitlement> entitlements, List<Plan> plans,
                           boolean checkoutAvailable, String culqiPublicKey) {}

    public Overview overview(UUID tenant) {
        Subscription s = subscriptions.find(tenant).orElse(null);
        Map<String, Entitlement> current = entitlements.effective(tenant);
        return new Overview(s == null ? "NONE" : s.status().name(), s == null ? null : s.planCode(),
                s == null ? null : s.pendingPlanCode(), s == null ? null : s.trialEndsAt(),
                s == null ? null : s.currentPeriodEnd(), s == null ? null : s.graceEndsAt(),
                current.values(), plans.publicPlans(), culqi.configured(), culqi.configured() ? culqi.publicKey() : null);
    }

    public record CheckoutInput(String planCode, String culqiToken, String email, String firstName, String lastName,
                                String phone, String address, String city) {}

    /**
     * Tokeniza la tarjeta en Culqi (el token viene de Culqi Checkout en el navegador), crea cliente,
     * tarjeta y suscripción. El estado queda PENDING_PAYMENT hasta que llegue el webhook.
     */
    @Transactional
    public Overview checkout(UUID tenant, CheckoutInput input) {
        var plan = plans.find(input.planCode()).filter(PlanRepository.PlanRow::publicPlan)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Plan inválido"));
        if (plan.culqiPlanId() == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El plan aún no está habilitado para pago");
        if (subscriptions.find(tenant).isEmpty()) subscriptions.startTrial(tenant, trialDays);
        Subscription s = subscriptions.findForUpdate(tenant).orElseThrow();
        if (s.status() == SubscriptionStatus.PENDING_PAYMENT && plan.code().equals(s.pendingPlanCode()))
            return overview(tenant); // Reintento del mismo checkout: idempotente.
        if (s.status() == SubscriptionStatus.ACTIVE && s.culqiSubscriptionId() != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El cambio de plan se habilitará en una siguiente versión");
        String customer = s.culqiCustomerId();
        if (customer == null) {
            customer = culqi.createCustomer(new CulqiClient.Customer(input.firstName(), input.lastName(), input.email(),
                    input.phone(), input.address(), input.city()));
        }
        String card = culqi.createCard(customer, input.culqiToken());
        subscriptions.saveCulqiCustomer(tenant, customer, card);
        var created = culqi.createSubscription(card, plan.culqiPlanId(),
                Map.of("tenant_id", tenant.toString(), "plan_code", plan.code()));
        subscriptions.markPending(tenant, plan.code(), created.path("id").asText());
        subscriptions.logEvent(tenant, s.status(), SubscriptionStatus.PENDING_PAYMENT, plan.code(), "CHECKOUT", null);
        return overview(tenant);
    }
}
