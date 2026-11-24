package com.saas.omnicanal.subscription.service;

import com.saas.omnicanal.subscription.model.Entitlement;
import com.saas.omnicanal.subscription.model.Plan;
import com.saas.omnicanal.subscription.model.Subscription;
import com.saas.omnicanal.subscription.model.SubscriptionStatus;
import com.saas.omnicanal.subscription.repository.PlanRepository;
import com.saas.omnicanal.subscription.repository.SubscriptionRepository;
import java.time.OffsetDateTime;
import tools.jackson.databind.JsonNode;
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
    /** Minutos tras los que un PENDING_PAYMENT sin confirmar se considera abandonado. */
    static final int STALE_PENDING_MINUTES = 15;
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
        // Doble clic o reintento inmediato del mismo checkout: idempotente. Si el pago lleva rato sin confirmarse
        // (webhook caído, tarjeta abandonada) se descarta ese intento y se empieza uno nuevo.
        if (s.status() == SubscriptionStatus.PENDING_PAYMENT && plan.code().equals(s.pendingPlanCode())
                && !subscriptions.unchangedFor(tenant, STALE_PENDING_MINUTES))
            return overview(tenant);
        if (s.status() == SubscriptionStatus.ACTIVE && s.culqiSubscriptionId() != null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El cambio de plan se habilitará en una siguiente versión");
        discardCulqiAttempt(s);
        String customer = s.culqiCustomerId();
        if (customer == null) {
            customer = culqi.createCustomer(new CulqiClient.Customer(input.firstName(), input.lastName(), input.email(),
                    input.phone(), input.address(), input.city()));
        }
        String card = culqi.createCard(customer, input.culqiToken());
        subscriptions.saveCulqiCustomer(tenant, customer, card, input.email());
        JsonNode created;
        try {
            created = culqi.createSubscription(card, plan.culqiPlanId(),
                    Map.of("tenant_id", tenant.toString(), "plan_code", plan.code()));
        } catch (ResponseStatusException ex) {
            // Culqi no deja dos suscripciones del mismo cliente al mismo plan ("Ya te encuentras registrado a este plan").
            if (ex.getReason() != null && ex.getReason().toLowerCase(java.util.Locale.ROOT).contains("registrad"))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Culqi todavía tiene una suscripción a este plan con "
                        + "tus datos y no pudimos liberarla. Escríbenos y la cancelamos para que puedas pagar de nuevo.", ex);
            throw ex;
        }
        subscriptions.markPending(tenant, plan.code(), created.path("id").asText());
        subscriptions.logEvent(tenant, s.status(), SubscriptionStatus.PENDING_PAYMENT, plan.code(), "CHECKOUT", null);
        return overview(tenant);
    }

    /**
     * Descarta el intento de suscripción en Culqi que la tienda tenga guardado: lo cancela en Culqi (si aún existe)
     * y limpia el id y el plan pendiente. Así un intento que quedó en PENDING_PAYMENT, o uno anulado desde el panel
     * del superadmin, no bloquea un checkout nuevo. Los eventos que lleguen del intento viejo se ignoran porque ya no
     * coinciden con ningún culqi_subscription_id.
     *
     * @return false si Culqi no confirmó la cancelación (el intento se limpia igual en la base).
     */
    @Transactional
    public boolean discardCulqiAttempt(Subscription s) {
        if (s.culqiSubscriptionId() == null && s.pendingPlanCode() == null) return true;
        boolean cancelled = s.culqiSubscriptionId() == null || culqi.cancelSubscription(s.culqiSubscriptionId());
        subscriptions.clearCulqiAttempt(s.tenantId());
        return cancelled;
    }
}
