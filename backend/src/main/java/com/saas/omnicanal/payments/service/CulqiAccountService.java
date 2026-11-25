package com.saas.omnicanal.payments.service;

import com.saas.omnicanal.payments.model.PaymentAccount;
import com.saas.omnicanal.payments.repository.PaymentAccountRepository;
import com.saas.omnicanal.shared.crypto.SecretCipher;
import com.saas.omnicanal.subscription.model.Entitlements;
import com.saas.omnicanal.subscription.service.EntitlementService;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Conexión de la cuenta Culqi propia de cada tienda con sus llaves pública y secreta. */
@Service
public class CulqiAccountService {
    static final Pattern PUBLIC_KEY = Pattern.compile("^pk_(test|live)_[A-Za-z0-9]{8,90}$");
    static final Pattern SECRET_KEY = Pattern.compile("^sk_(test|live)_[A-Za-z0-9]{8,90}$");

    private final PaymentAccountRepository accounts;
    private final StoreCulqiClient culqi;
    private final SecretCipher cipher;
    private final EntitlementService entitlements;
    private final String publicApiBase;

    public CulqiAccountService(PaymentAccountRepository accounts, StoreCulqiClient culqi, SecretCipher cipher,
                               EntitlementService entitlements, @Value("${PUBLIC_API_BASE_URL:}") String publicApiBase) {
        this.accounts = accounts;
        this.culqi = culqi;
        this.cipher = cipher;
        this.entitlements = entitlements;
        this.publicApiBase = publicApiBase.replaceAll("/+$", "");
    }

    static String context(UUID store) { return "culqi/secret-key/" + store; }

    public String webhookUrl(UUID store) {
        return publicApiBase.isBlank() ? null : publicApiBase + "/api/webhooks/culqi/stores/" + store;
    }

    public PaymentAccount account(UUID store) {
        boolean available = cipher.configured();
        return accounts.find(store).map(a -> new PaymentAccount(available, true, "CULQI", a.publicKey(),
                        hint(store, a.secretKey(), a.liveMode()), a.liveMode(), a.status(), a.lastError(), a.linkedAt(), webhookUrl(store)))
                .orElse(new PaymentAccount(available, false, "CULQI", null, null, false, "NOT_LINKED", null, null, webhookUrl(store)));
    }

    /** "sk_live_••••abcd": suficiente para que el dueño reconozca la llave sin exponerla. */
    private String hint(UUID store, byte[] secret, boolean live) {
        String prefix = live ? "sk_live_••••" : "sk_test_••••";
        try {
            String sk = cipher.decrypt(context(store), secret);
            return prefix + sk.substring(Math.max(0, sk.length() - 4));
        } catch (RuntimeException ex) {
            return prefix;
        }
    }

    /**
     * Valida las llaves con Culqi ({@code GET /v2/charges?limit=1}) y las guarda: la pública visible para la tienda
     * web, la secreta cifrada. Ambas deben ser del mismo modo (prueba o producción).
     */
    @Transactional
    public PaymentAccount connect(UUID store, String publicKey, String secretKey) {
        String pk = publicKey == null ? "" : publicKey.strip();
        String sk = secretKey == null ? "" : secretKey.strip();
        if (!PUBLIC_KEY.matcher(pk).matches())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La llave pública debe empezar con pk_test_ o pk_live_");
        if (!SECRET_KEY.matcher(sk).matches())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La llave secreta debe empezar con sk_test_ o sk_live_");
        boolean live = sk.startsWith("sk_live_");
        if (live != pk.startsWith("pk_live_"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Las dos llaves deben ser del mismo modo: ambas de prueba o ambas de producción");
        if (!cipher.configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Falta SECRETS_ENCRYPTION_KEY en el servidor");
        entitlements.requireEnabled(store, Entitlements.PAYMENTS_ONLINE);
        if (!culqi.validSecretKey(sk))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Culqi rechazó la llave secreta. Cópiala de nuevo desde tu panel de Culqi.");
        accounts.upsert(store, pk, cipher.encrypt(context(store), sk), live);
        return account(store);
    }

    @Transactional
    public void disconnect(UUID store) {
        accounts.delete(store);
    }

    public record Active(String publicKey, String secretKey, boolean liveMode) {
        @Override public String toString() { return "Active[liveMode=" + liveMode + "]"; }
    }

    /** Llaves de la tienda si tiene Culqi activo y su plan permite cobros en línea. */
    public Optional<Active> active(UUID store) {
        if (!entitlements.enabled(store, Entitlements.PAYMENTS_ONLINE)) return Optional.empty();
        return accounts.find(store).filter(a -> "ACTIVE".equals(a.status()))
                .map(a -> new Active(a.publicKey(), cipher.decrypt(context(store), a.secretKey()), a.liveMode()));
    }

    public void markError(UUID store, String error) {
        accounts.markError(store, error);
    }

    public record PublicConfig(boolean enabled, String provider, String publicKey, boolean liveMode) {}

    public PublicConfig publicConfig(UUID store) {
        if (!entitlements.enabled(store, Entitlements.PAYMENTS_ONLINE)) return new PublicConfig(false, null, null, false);
        return accounts.find(store).filter(a -> "ACTIVE".equals(a.status()))
                .map(a -> new PublicConfig(true, "CULQI", a.publicKey(), a.liveMode()))
                .orElse(new PublicConfig(false, null, null, false));
    }
}
