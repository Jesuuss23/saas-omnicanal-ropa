package com.saas.omnicanal.payments.controller;

import static com.saas.omnicanal.shared.security.OwnerAccess.requireOwner;

import com.saas.omnicanal.payments.model.PaymentAccount;
import com.saas.omnicanal.payments.service.MercadoPagoAccountService;
import java.net.URI;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** El dueño vincula su propia cuenta de Mercado Pago para cobrar los pedidos de su tienda. */
@RestController
class OwnerMercadoPagoController {
    private final MercadoPagoAccountService accounts;

    OwnerMercadoPagoController(MercadoPagoAccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/api/owner/stores/{store}/payments/mercadopago")
    ResponseEntity<PaymentAccount> get(@PathVariable UUID store, Principal owner) {
        requireOwner(store, owner);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.account(store));
    }

    @PostMapping("/api/owner/stores/{store}/payments/mercadopago/authorize")
    Map<String, String> authorize(@PathVariable UUID store, Principal owner) {
        requireOwner(store, owner);
        return Map.of("url", accounts.authorizationUrl(store));
    }

    @DeleteMapping("/api/owner/stores/{store}/payments/mercadopago")
    ResponseEntity<Void> unlink(@PathVariable UUID store, Principal owner) {
        requireOwner(store, owner);
        accounts.unlink(store);
        return ResponseEntity.noContent().build();
    }

    /** redirect_uri registrado en la app de Mercado Pago. Vuelve al panel de la tienda con el resultado. */
    @GetMapping("/api/owner/payments/mercadopago/callback")
    ResponseEntity<Void> callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
                                  @RequestParam(required = false) String error, Principal owner) {
        if (owner == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String target = "/propietario/" + owner.getName() + "/cobros?mercadopago=";
        if (error != null) return redirect(target + "cancelled");
        try {
            accounts.completeAuthorization(state, code, owner.getName());
            return redirect(target + "linked");
        } catch (ResponseStatusException ex) {
            return redirect(target + "error");
        }
    }

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
    }
}
