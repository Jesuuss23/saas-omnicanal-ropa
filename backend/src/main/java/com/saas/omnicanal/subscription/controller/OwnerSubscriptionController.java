package com.saas.omnicanal.subscription.controller;

import static com.saas.omnicanal.shared.security.OwnerAccess.requireOwner;

import com.saas.omnicanal.subscription.service.SubscriptionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Plan y suscripción de la tienda al SaaS (cobro con Culqi). */
@RestController
@RequestMapping("/api/owner/stores/{store}/subscription")
class OwnerSubscriptionController {
    private final SubscriptionService subscriptions;

    OwnerSubscriptionController(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @GetMapping
    ResponseEntity<SubscriptionService.Overview> get(@PathVariable UUID store, Principal owner) {
        requireOwner(store, owner);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(subscriptions.overview(store));
    }

    record CheckoutInput(@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,31}") String planCode,
                         @NotBlank @Pattern(regexp = "tkn_[A-Za-z0-9_]{6,64}") String culqiToken,
                         @NotBlank @Email @Size(max = 254) String email,
                         @NotBlank @Size(max = 50) String firstName, @NotBlank @Size(max = 50) String lastName,
                         @NotBlank @Pattern(regexp = "[+]?[0-9]{6,15}") String phone,
                         @NotBlank @Size(min = 5, max = 100) String address, @NotBlank @Size(max = 30) String city) {}

    @PostMapping("/checkout")
    SubscriptionService.Overview checkout(@PathVariable UUID store, Principal owner, @Valid @RequestBody CheckoutInput input) {
        requireOwner(store, owner);
        return subscriptions.checkout(store, new SubscriptionService.CheckoutInput(input.planCode(), input.culqiToken(),
                input.email().strip(), input.firstName().strip(), input.lastName().strip(), input.phone(),
                input.address().strip(), input.city().strip()));
    }
}
