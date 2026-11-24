package com.saas.omnicanal.contact.controller;

import java.time.Duration;
import java.util.UUID;
import org.springframework.http.ResponseCookie;

/**
 * Cookie de sesión del comprador. Vale solo bajo /api/public/stores/{tienda}, así cada tienda tiene su propia sesión
 * y el checkout de esa tienda la recibe. HttpOnly y SameSite=Lax: el navegador no la manda en POST de otros sitios.
 */
public final class CustomerCookie {
    public static final String NAME = "omni_customer";

    private CustomerCookie() {}

    public static ResponseCookie issue(UUID store, String token, Duration maxAge, boolean secure) {
        return ResponseCookie.from(NAME, token).path("/api/public/stores/" + store).httpOnly(true).secure(secure)
                .sameSite("Lax").maxAge(maxAge).build();
    }

    public static ResponseCookie clear(UUID store, boolean secure) {
        return ResponseCookie.from(NAME, "").path("/api/public/stores/" + store).httpOnly(true).secure(secure)
                .sameSite("Lax").maxAge(0).build();
    }
}
