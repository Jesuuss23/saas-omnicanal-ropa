package com.saas.omnicanal.contact.controller;

import com.saas.omnicanal.contact.model.ContactViews.Profile;
import com.saas.omnicanal.contact.service.CustomerAccountService;
import com.saas.omnicanal.marketing.model.MarketingModels.Preferences;
import com.saas.omnicanal.marketing.service.MarketingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Cuenta del comprador en la tienda pública (ver CustomerAccountSecurity). */
@RestController
@RequestMapping("/api/public/stores/{store}/account")
class CustomerAccountController {
    private final CustomerAccountService accounts;
    private final MarketingService marketing;
    private final boolean secureCookie;

    CustomerAccountController(CustomerAccountService accounts, MarketingService marketing,
                              @Value("${app.base-url:http://localhost:3000}") String baseUrl) {
        this.accounts = accounts;
        this.marketing = marketing;
        this.secureCookie = baseUrl.startsWith("https://");
    }

    record PreferencesIn(boolean emailOptIn, boolean whatsappOptIn) {}

    @GetMapping("/preferences")
    ResponseEntity<Preferences> preferences(@PathVariable UUID store, @CookieValue(name = CustomerCookie.NAME, required = false) String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(marketing.preferences(store, signedIn(store, token)));
    }

    @PutMapping(path = "/preferences", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Preferences> savePreferences(@PathVariable UUID store, @CookieValue(name = CustomerCookie.NAME, required = false) String token,
                                                @RequestBody PreferencesIn in) {
        var saved = marketing.savePreferences(store, signedIn(store, token), in.emailOptIn(), in.whatsappOptIn());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(saved);
    }

    private UUID signedIn(UUID store, String token) {
        return accounts.contactFor(store, token).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    record GoogleSignIn(@NotBlank @Size(max = 4096) String credential) {}

    @GetMapping("/config")
    Map<String, Object> config() {
        var body = new java.util.HashMap<String, Object>();
        body.put("googleClientId", accounts.googleClientId());
        return body;
    }

    @GetMapping
    ResponseEntity<Profile> me(@PathVariable UUID store, @CookieValue(name = CustomerCookie.NAME, required = false) String token) {
        UUID contact = accounts.contactFor(store, token).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.profile(store, contact));
    }

    @PostMapping(path = "/google", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Profile> google(@PathVariable UUID store, @Valid @RequestBody GoogleSignIn in) {
        var session = accounts.signInWithGoogle(store, in.credential());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, CustomerCookie.issue(store, session.token(), session.maxAge(), secureCookie).toString())
                .body(session.profile());
    }

    @PostMapping(path = "/logout", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> logout(@PathVariable UUID store, @CookieValue(name = CustomerCookie.NAME, required = false) String token) {
        accounts.signOut(token);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, CustomerCookie.clear(store, secureCookie).toString()).build();
    }
}
