package com.saas.omnicanal.subscription.controller;

import com.saas.omnicanal.subscription.repository.PlanRepository;
import com.saas.omnicanal.subscription.repository.SubscriptionRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Superadmin: planes, vínculo con planes de Culqi, overrides de entitlements y panel comercial. */
@RestController
@RequestMapping("/api/superadmin")
class AdminPlansController {
    private final PlanRepository plans;
    private final SubscriptionRepository subscriptions;

    AdminPlansController(PlanRepository plans, SubscriptionRepository subscriptions) {
        this.plans = plans;
        this.subscriptions = subscriptions;
    }

    @GetMapping("/plans")
    List<PlanRepository.PlanRow> plans() {
        return plans.all();
    }

    record CulqiPlan(@NotBlank @Pattern(regexp = "pln_[A-Za-z0-9_]{4,60}") String culqiPlanId) {}

    @PutMapping("/plans/{code}/culqi")
    Map<String, String> linkCulqi(@PathVariable String code, @Valid @RequestBody CulqiPlan input) {
        if (!plans.setCulqiPlanId(code, input.culqiPlanId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return Map.of("code", code, "culqiPlanId", input.culqiPlanId());
    }

    record Override(@NotNull Boolean enabled, @PositiveOrZero Long limit, OffsetDateTime expiresAt,
                    @NotBlank @Size(max = 200) String reason) {}

    @PutMapping("/tenants/{tenant}/entitlements/{key}")
    Map<String, Object> override(@PathVariable UUID tenant, @PathVariable @Pattern(regexp = "[a-z][a-z0-9_.]{1,63}") String key,
                                 @Valid @RequestBody Override input) {
        if (!key.matches("[a-z][a-z0-9_.]{1,63}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        plans.upsertOverride(tenant, key, input.enabled(), input.limit(), input.expiresAt(), input.reason().strip());
        return Map.of("tenant", tenant, "key", key, "enabled", input.enabled());
    }

    @DeleteMapping("/tenants/{tenant}/entitlements/{key}")
    void removeOverride(@PathVariable UUID tenant, @PathVariable String key) {
        plans.deleteOverride(tenant, key);
    }

    @GetMapping("/subscriptions/summary")
    Map<String, Long> summary() {
        return subscriptions.countByStatus();
    }
}
