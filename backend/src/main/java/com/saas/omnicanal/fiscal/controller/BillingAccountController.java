package com.saas.omnicanal.fiscal.controller;

import static com.saas.omnicanal.shared.security.OwnerAccess.requireOwner;

import com.saas.omnicanal.fiscal.service.BillingAccountService;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Llaves de Nubefact de la tienda: ver estado, guardar y probar, volver a probar y desconectar. */
@RestController
@RequestMapping("/api/owner/stores/{store}/billing/nubefact")
class BillingAccountController {
    private final BillingAccountService accounts;

    BillingAccountController(BillingAccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    ResponseEntity<BillingAccountService.Account> get(@PathVariable UUID store, Principal owner) {
        requireOwner(store, owner);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.account(store));
    }

    @PutMapping
    BillingAccountService.Account connect(@PathVariable UUID store, Principal owner, @RequestBody BillingAccountService.Input input) {
        requireOwner(store, owner);
        return accounts.connect(store, input);
    }

    @PostMapping("/test")
    BillingAccountService.Account test(@PathVariable UUID store, Principal owner) {
        requireOwner(store, owner);
        return accounts.test(store);
    }

    @DeleteMapping
    ResponseEntity<Void> disconnect(@PathVariable UUID store, Principal owner) {
        requireOwner(store, owner);
        accounts.disconnect(store);
        return ResponseEntity.noContent().build();
    }
}
