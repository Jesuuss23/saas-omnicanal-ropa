package com.saas.omnicanal.payments.service;

import com.saas.omnicanal.payments.model.PaymentAccount;
import com.saas.omnicanal.payments.repository.OAuthStateRepository;
import com.saas.omnicanal.payments.repository.PaymentAccountRepository;
import com.saas.omnicanal.shared.crypto.SecretCipher;
import com.saas.omnicanal.subscription.model.Entitlements;
import com.saas.omnicanal.subscription.service.EntitlementService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Vinculación OAuth de la cuenta de Mercado Pago del dueño y gestión de sus tokens (cifrados). */
@Service
public class MercadoPagoAccountService {
    private final PaymentAccountRepository accounts;
    private final OAuthStateRepository states;
    private final MercadoPagoClient client;
    private final SecretCipher cipher;
    private final EntitlementService entitlements;
    private final SecureRandom random = new SecureRandom();

    public MercadoPagoAccountService(PaymentAccountRepository accounts, OAuthStateRepository states,
                                     MercadoPagoClient client, SecretCipher cipher, EntitlementService entitlements) {
        this.accounts = accounts;
        this.states = states;
        this.client = client;
        this.cipher = cipher;
        this.entitlements = entitlements;
    }

    static String accessContext(UUID store) { return "mercadopago/access-token/" + store; }
    static String refreshContext(UUID store) { return "mercadopago/refresh-token/" + store; }

    public PaymentAccount account(UUID store) {
        boolean available = client.configured() && cipher.configured();
        return accounts.find(store).map(a -> new PaymentAccount(available, true, a.providerUserId(), a.liveMode(),
                        a.status(), a.lastError(), a.linkedAt(), a.tokenExpiresAt()))
                .orElse(new PaymentAccount(available, false, null, false, "NOT_LINKED", null, null, null));
    }

    @Transactional
    public String authorizationUrl(UUID store) {
        if (!client.configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Mercado Pago no está configurado");
        if (!cipher.configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Falta SECRETS_ENCRYPTION_KEY");
        entitlements.requireEnabled(store, Entitlements.PAYMENTS_MERCADOPAGO);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        states.save(sha256(state), store);
        return client.authorizationUrl(state);
    }

    /** Callback OAuth: el state es de un solo uso y debe pertenecer a la tienda con sesión iniciada. */
    @Transactional
    public UUID completeAuthorization(String state, String code, String sessionStore) {
        if (state == null || code == null || state.length() > 100 || code.length() > 512)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        UUID store = states.consume(sha256(state)).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST));
        if (!store.toString().equals(sessionStore)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        save(store, client.exchangeCode(code));
        return store;
    }

    private void save(UUID store, MercadoPagoClient.Tokens t) {
        OffsetDateTime expires = t.expiresIn() > 0 ? OffsetDateTime.now().plusSeconds(t.expiresIn()) : null;
        accounts.upsert(store, t.userId(), t.publicKey(), cipher.encrypt(accessContext(store), t.accessToken()),
                t.refreshToken() == null ? null : cipher.encrypt(refreshContext(store), t.refreshToken()), expires, t.liveMode());
    }

    @Transactional
    public void unlink(UUID store) {
        accounts.delete(store);
    }

    /** Token vigente del vendedor; se renueva si vence en menos de 7 días. */
    @Transactional
    public String accessToken(UUID store) {
        var account = accounts.find(store)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "La tienda no vinculó Mercado Pago"));
        boolean expiring = account.tokenExpiresAt() != null && account.tokenExpiresAt().isBefore(OffsetDateTime.now().plusDays(7));
        if (expiring && account.refreshToken() != null) {
            try {
                var refreshed = client.refresh(cipher.decrypt(refreshContext(store), account.refreshToken()));
                save(store, refreshed);
                return refreshed.accessToken();
            } catch (ResponseStatusException ex) {
                accounts.markError(store, "No se pudo renovar el token: " + ex.getReason());
            }
        }
        return cipher.decrypt(accessContext(store), account.accessToken());
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
