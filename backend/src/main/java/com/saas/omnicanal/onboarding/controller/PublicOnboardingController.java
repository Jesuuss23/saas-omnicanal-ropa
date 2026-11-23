package com.saas.omnicanal.onboarding.controller;

import com.saas.omnicanal.onboarding.model.SignupInput;
import com.saas.omnicanal.onboarding.service.PasswordResetService;
import com.saas.omnicanal.onboarding.service.SignupService;
import com.saas.omnicanal.subscription.model.Plan;
import com.saas.omnicanal.subscription.repository.PlanRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints públicos del sitio de venta: planes, prueba gratis y recuperación de acceso. */
@RestController
@RequestMapping("/api/public")
class PublicOnboardingController {
    private final PlanRepository plans;
    private final SignupService signups;
    private final PasswordResetService resets;

    PublicOnboardingController(PlanRepository plans, SignupService signups, PasswordResetService resets) {
        this.plans = plans;
        this.signups = signups;
        this.resets = resets;
    }

    record Token(@NotBlank @Size(max = 64) String token) {}
    record Confirm(@NotBlank @Size(max = 64) String token, @NotBlank @Size(max = 200) String password) {}
    record ResetRequest(@NotBlank @Email @Size(max = 254) String email) {}
    record ResetConfirm(@NotNull UUID store, @NotBlank @Size(max = 64) String token, @NotBlank @Size(max = 200) String password) {}

    @GetMapping("/plans")
    List<Plan> plans() {
        return plans.publicPlans();
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Boolean> signup(@Valid @RequestBody SignupInput input) {
        signups.request(input);
        return Map.of("sent", true);
    }

    @PostMapping("/signup/preview")
    SignupService.Preview preview(@Valid @RequestBody Token input) {
        return signups.preview(input.token());
    }

    @PostMapping("/signup/confirm")
    SignupService.Created confirm(@Valid @RequestBody Confirm input) {
        return signups.confirm(input.token(), input.password());
    }

    @PostMapping("/password-reset")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Boolean> requestReset(@Valid @RequestBody ResetRequest input) {
        resets.request(input.email());
        return Map.of("sent", true);
    }

    @PostMapping("/password-reset/confirm")
    Map<String, Boolean> confirmReset(@Valid @RequestBody ResetConfirm input) {
        resets.confirm(input.store(), input.token(), input.password());
        return Map.of("updated", true);
    }
}
