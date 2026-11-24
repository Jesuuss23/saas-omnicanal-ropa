package com.saas.omnicanal.platformadmin.service;

import com.saas.omnicanal.platformadmin.model.PageResult;
import com.saas.omnicanal.platformadmin.model.SubscriptionChange;
import com.saas.omnicanal.platformadmin.repository.AdminConsoleRepository;
import com.saas.omnicanal.platformadmin.repository.AdminConsoleRepository.*;
import com.saas.omnicanal.platformadmin.repository.AuditRepository;
import com.saas.omnicanal.subscription.model.Subscription;
import com.saas.omnicanal.subscription.model.SubscriptionStatus;
import com.saas.omnicanal.subscription.repository.PlanRepository;
import com.saas.omnicanal.subscription.repository.SubscriptionRepository;
import com.saas.omnicanal.subscription.service.SubscriptionService;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Consola de la plataforma: métricas, fichas de tienda, suspensión (kill switch) y ajustes manuales de suscripción. */
@Service
public class AdminConsoleService {
    private static final int MAX_PAGE_SIZE = 100;

    private final AdminConsoleRepository console;
    private final AuditRepository auditLog;
    private final AuditService audit;
    private final SubscriptionRepository subscriptions;
    private final PlanRepository plans;
    private final SubscriptionService lifecycle;

    public AdminConsoleService(AdminConsoleRepository console, AuditRepository auditLog, AuditService audit,
                               SubscriptionRepository subscriptions, PlanRepository plans, SubscriptionService lifecycle) {
        this.console = console;
        this.auditLog = auditLog;
        this.audit = audit;
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.lifecycle = lifecycle;
    }

    public record Dashboard(String email, Kpis kpis, List<WeekCount> newStores, Map<String, Long> statuses,
                            List<TenantRow> recent, List<AuditRepository.Entry> activity) {}

    public record Detail(TenantDetail store, List<SubscriptionEvent> events, List<PlanRepository.OverrideRow> overrides,
                         List<AuditRepository.Entry> audit) {}

    public Dashboard dashboard(Principal principal) {
        return new Dashboard(principal.getName(), console.kpis(), console.newStoresByWeek(12), console.statusCounts(),
                PageResult.of(console.tenants(null, null, 0, 6), 0, 6).items(),
                PageResult.of(auditLog.page(null, 0, 8), 0, 8).items());
    }

    public PageResult<TenantRow> tenants(String query, String status, int page, int size) {
        int s = clamp(size);
        int p = Math.max(page, 0);
        return PageResult.of(console.tenants(query, status, p, s), p, s);
    }

    public Detail tenant(UUID id) {
        var store = console.tenant(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new Detail(store, console.subscriptionEvents(id, 20), plans.overrides(id),
                PageResult.of(auditLog.page(id, 0, 20), 0, 20).items());
    }

    @Transactional
    public Detail suspend(Principal actor, UUID id, String reason) {
        requireTenant(id);
        if (!console.suspend(id, reason.strip())) throw new ResponseStatusException(HttpStatus.CONFLICT, "La tienda ya está suspendida.");
        audit.record(actor, AuditService.TENANT_SUSPENDED, id, reason.strip());
        return tenant(id);
    }

    @Transactional
    public Detail reactivate(Principal actor, UUID id) {
        requireTenant(id);
        if (!console.reactivate(id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "La tienda no está suspendida.");
        audit.record(actor, AuditService.TENANT_REACTIVATED, id, null);
        return tenant(id);
    }

    @Transactional
    public Detail changeSubscription(Principal actor, UUID id, SubscriptionChange change) {
        requireTenant(id);
        Subscription current = subscriptions.findForUpdate(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT, "La tienda no tiene suscripción."));
        String reason = change.reason().strip();
        // Un ajuste manual reemplaza cualquier intento de pago con Culqi que no llegó a confirmarse: se cancela en Culqi
        // y se limpia, para que el propietario pueda volver a suscribirse. Cancelar también corta el cobro recurrente.
        // Activar a mano sobre un plan ya pagado por Culqi (ACTIVE o GRACE) no toca esa suscripción.
        boolean paidByCulqi = current.status() == SubscriptionStatus.ACTIVE || current.status() == SubscriptionStatus.GRACE;
        String culqiNote = "";
        if (change.action() == SubscriptionChange.Action.CANCEL || !paidByCulqi) {
            boolean hadAttempt = current.culqiSubscriptionId() != null || current.pendingPlanCode() != null;
            if (hadAttempt) culqiNote = lifecycle.discardCulqiAttempt(current)
                    ? " Intento de Culqi descartado." : " Culqi no confirmó la cancelación del intento; revísalo en su panel.";
        }
        switch (change.action()) {
            case EXTEND_TRIAL -> {
                if (change.days() == null) throw bad("Indica cuántos días extender.");
                if (current.status() == SubscriptionStatus.ACTIVE) throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "La tienda ya tiene un plan activo.");
                subscriptions.extendTrial(id, change.days());
                subscriptions.logEvent(id, current.status(), SubscriptionStatus.TRIAL, current.planCode(), "SUPERADMIN",
                        "+" + change.days() + " días: " + reason);
                audit.record(actor, AuditService.SUBSCRIPTION_CHANGED, id, "Prueba +" + change.days() + " días. " + reason + culqiNote);
            }
            case ACTIVATE -> {
                if (change.planCode() == null || change.months() == null) throw bad("Indica plan y meses.");
                var plan = plans.find(change.planCode()).orElseThrow(() -> bad("Plan inválido."));
                subscriptions.activate(id, plan.code(), OffsetDateTime.now().plusMonths(change.months()));
                subscriptions.logEvent(id, current.status(), SubscriptionStatus.ACTIVE, plan.code(), "SUPERADMIN",
                        change.months() + " meses: " + reason);
                audit.record(actor, AuditService.SUBSCRIPTION_CHANGED, id,
                        "Plan " + plan.code() + " por " + change.months() + " meses. " + reason + culqiNote);
            }
            case CANCEL -> {
                if (current.status() == SubscriptionStatus.CANCELLED) throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "La suscripción ya está cancelada.");
                subscriptions.setStatus(id, SubscriptionStatus.CANCELLED, null);
                subscriptions.logEvent(id, current.status(), SubscriptionStatus.CANCELLED, current.planCode(), "SUPERADMIN", reason);
                audit.record(actor, AuditService.SUBSCRIPTION_CHANGED, id, "Cancelada. " + reason + culqiNote);
            }
        }
        return tenant(id);
    }

    public PageResult<PaymentEvent> payments(String provider, String status, int page, int size) {
        int s = clamp(size);
        int p = Math.max(page, 0);
        return PageResult.of(console.paymentEvents(provider, status, p, s), p, s);
    }

    public PageResult<AuditRepository.Entry> audit(UUID tenant, int page, int size) {
        int s = clamp(size);
        int p = Math.max(page, 0);
        return PageResult.of(auditLog.page(tenant, p, s), p, s);
    }

    private void requireTenant(UUID id) {
        if (console.tenant(id).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static int clamp(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }
}
