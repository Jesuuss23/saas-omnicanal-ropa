package com.saas.omnicanal.payments.controller;

import com.saas.omnicanal.payments.service.MercadoPagoCheckoutService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** El comprador invitado inicia el pago con el token de su enlace de seguimiento. */
@RestController
class PublicOrderPaymentController {
    private final MercadoPagoCheckoutService checkout;

    PublicOrderPaymentController(MercadoPagoCheckoutService checkout) {
        this.checkout = checkout;
    }

    @PostMapping("/api/public/stores/{store}/orders/{order}/mercadopago")
    ResponseEntity<MercadoPagoCheckoutService.Checkout> start(@PathVariable UUID store, @PathVariable UUID order,
            @RequestHeader(value = "X-Order-Token", defaultValue = "") String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(checkout.start(store, order, token));
    }
}
