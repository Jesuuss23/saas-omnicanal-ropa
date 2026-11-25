package com.saas.omnicanal.payments.controller;

import static com.saas.omnicanal.shared.security.OwnerAccess.requireOwner;

import com.saas.omnicanal.payments.model.PaymentAccount;
import com.saas.omnicanal.payments.service.CulqiAccountService;
import com.saas.omnicanal.payments.service.CulqiChargeService;
import com.saas.omnicanal.payments.service.CulqiStoreWebhookService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Cobros en línea con Culqi: conexión del dueño, configuración pública, cobro del pedido y webhook de respaldo. */
@RestController
class CulqiPaymentsController {
    private final CulqiAccountService accounts;
    private final CulqiChargeService charges;
    private final CulqiStoreWebhookService webhooks;

    CulqiPaymentsController(CulqiAccountService accounts, CulqiChargeService charges, CulqiStoreWebhookService webhooks) {
        this.accounts = accounts;
        this.charges = charges;
        this.webhooks = webhooks;
    }

    record Keys(@NotBlank @Size(max = 100) String publicKey, @NotBlank @Size(max = 100) String secretKey) {
        @Override public String toString() { return "Keys[publicKey=" + publicKey + "]"; }
    }

    @GetMapping("/api/owner/stores/{store}/payments/culqi")
    ResponseEntity<PaymentAccount> get(@PathVariable UUID store, Principal owner) {
        requireOwner(store, owner);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.account(store));
    }

    @PutMapping("/api/owner/stores/{store}/payments/culqi")
    PaymentAccount connect(@PathVariable UUID store, Principal owner, @Valid @RequestBody Keys keys) {
        requireOwner(store, owner);
        return accounts.connect(store, keys.publicKey(), keys.secretKey());
    }

    @DeleteMapping("/api/owner/stores/{store}/payments/culqi")
    ResponseEntity<Void> disconnect(@PathVariable UUID store, Principal owner) {
        requireOwner(store, owner);
        accounts.disconnect(store);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/public/stores/{store}/payment-config")
    ResponseEntity<CulqiAccountService.PublicConfig> config(@PathVariable UUID store) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.publicConfig(store));
    }

    @PostMapping("/api/public/stores/{store}/orders/{order}/culqi/pay")
    ResponseEntity<CulqiChargeService.PayResult> pay(@PathVariable UUID store, @PathVariable UUID order,
            @RequestHeader(value = "X-Order-Token", defaultValue = "") String token,
            @RequestBody CulqiChargeService.PayRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(charges.pay(store, order, token, body));
    }

    @PostMapping(path = "/api/webhooks/culqi/stores/{store}", consumes = MediaType.ALL_VALUE)
    Map<String, String> webhook(@PathVariable UUID store, @RequestBody(required = false) String body) {
        return webhooks.receive(store, body);
    }
}
