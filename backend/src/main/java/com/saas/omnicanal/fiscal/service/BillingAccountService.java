package com.saas.omnicanal.fiscal.service;

import com.saas.omnicanal.shared.crypto.SecretCipher;
import com.saas.omnicanal.subscription.model.Entitlements;
import com.saas.omnicanal.subscription.service.EntitlementService;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Conexión de cada tienda con su propia cuenta de Nubefact (RUTA, TOKEN cifrado, ambiente y series). */
@Service
public class BillingAccountService {
    /** Solo dominios de Nubefact: la RUTA la escribe el dueño y el backend la llama (evita SSRF). */
    static final Pattern API_URL = Pattern.compile("^https://([a-z0-9-]+\\.)*nubefact\\.com(/[A-Za-z0-9_.-]+)+/?$");
    static final Pattern INVOICE_SERIES = Pattern.compile("^F[A-Z0-9]{3}$");
    static final Pattern RECEIPT_SERIES = Pattern.compile("^B[A-Z0-9]{3}$");

    private final JdbcTemplate db;
    private final SecretCipher cipher;
    private final InvoiceProvider provider;
    private final EntitlementService entitlements;

    public BillingAccountService(JdbcTemplate db, SecretCipher cipher, InvoiceProvider provider, EntitlementService entitlements) {
        this.db = db;
        this.cipher = cipher;
        this.provider = provider;
        this.entitlements = entitlements;
    }

    public record Account(boolean available, boolean linked, String provider, String apiUrl, String tokenHint,
                          String environment, String invoiceSeries, String receiptSeries, String status,
                          String lastError, OffsetDateTime verifiedAt) {}
    public record Input(String apiUrl, String token, String environment, String invoiceSeries, String receiptSeries) {
        @Override public String toString() { return "Input[redacted]"; }
    }
    /** Credenciales descifradas para emitir. */
    public record Active(InvoiceProvider.Credentials credentials, String environment, String invoiceSeries, String receiptSeries) {}

    private record Row(String apiUrl, byte[] token, String environment, String invoiceSeries, String receiptSeries,
                       String status, String lastError, OffsetDateTime verifiedAt) {}

    static String context(UUID store) { return "nubefact/token/" + store; }

    private Optional<Row> row(UUID store) {
        return db.query("""
                SELECT api_url, api_token_ciphertext, environment, invoice_series, receipt_series, status, last_error, verified_at
                  FROM store_billing_account WHERE tenant_id=?
                """, (rs, n) -> new Row(rs.getString(1), rs.getBytes(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getObject(8, OffsetDateTime.class)), store).stream().findFirst();
    }

    public Account account(UUID store) {
        return row(store).map(r -> new Account(cipher.configured(), true, "NUBEFACT", r.apiUrl(), hint(store, r.token()),
                        r.environment(), r.invoiceSeries(), r.receiptSeries(), r.status(), r.lastError(), r.verifiedAt()))
                .orElse(new Account(cipher.configured(), false, "NUBEFACT", null, null, "BETA", null, null, "NOT_LINKED", null, null));
    }

    private String hint(UUID store, byte[] token) {
        try {
            String t = cipher.decrypt(context(store), token);
            return "••••" + t.substring(Math.max(0, t.length() - 4));
        } catch (RuntimeException ex) {
            return "••••";
        }
    }

    /** Valida con Nubefact y guarda. Si el TOKEN viene vacío se conserva el guardado (para cambiar solo series o ambiente). */
    public Account connect(UUID store, Input input) {
        String url = input.apiUrl() == null ? "" : input.apiUrl().strip();
        String env = input.environment() == null ? "BETA" : input.environment().strip().toUpperCase(java.util.Locale.ROOT);
        String invoice = input.invoiceSeries() == null ? "" : input.invoiceSeries().strip().toUpperCase(java.util.Locale.ROOT);
        String receipt = input.receiptSeries() == null ? "" : input.receiptSeries().strip().toUpperCase(java.util.Locale.ROOT);
        if (!API_URL.matcher(url).matches())
            throw bad("La RUTA debe ser la URL https de tu cuenta de Nubefact, por ejemplo https://api.nubefact.com/api/v1/xxxx.");
        if (!env.equals("BETA") && !env.equals("PRODUCTION")) throw bad("El ambiente debe ser BETA (pruebas) o PRODUCTION.");
        if (!INVOICE_SERIES.matcher(invoice).matches()) throw bad("La serie de facturas debe tener 4 caracteres y empezar con F (ej. FFF1).");
        if (!RECEIPT_SERIES.matcher(receipt).matches()) throw bad("La serie de boletas debe tener 4 caracteres y empezar con B (ej. BBB1).");
        if (!cipher.configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Falta SECRETS_ENCRYPTION_KEY en el servidor.");
        if (env.equals("PRODUCTION")) entitlements.requireEnabled(store, Entitlements.FISCAL_PRODUCTION);
        String token = input.token() == null ? "" : input.token().strip();
        if (token.isEmpty()) {
            token = row(store).map(r -> cipher.decrypt(context(store), r.token()))
                    .orElseThrow(() -> bad("Pega el TOKEN de tu cuenta de Nubefact."));
        } else if (token.length() < 20 || token.length() > 200 || !token.matches("[A-Za-z0-9_-]+")) {
            throw bad("El TOKEN no tiene el formato de Nubefact. Cópialo completo desde API - Integración.");
        }
        try {
            provider.test(new InvoiceProvider.Credentials(url, token), receipt);
        } catch (InvoiceProvider.ProviderException ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        }
        db.update("""
                INSERT INTO store_billing_account(tenant_id, api_url, api_token_ciphertext, environment, invoice_series,
                                                  receipt_series, status, last_error, verified_at, updated_at)
                VALUES (?,?,?,?,?,?,'ACTIVE',NULL,now(),now())
                ON CONFLICT (tenant_id) DO UPDATE SET api_url=EXCLUDED.api_url, api_token_ciphertext=EXCLUDED.api_token_ciphertext,
                    environment=EXCLUDED.environment, invoice_series=EXCLUDED.invoice_series, receipt_series=EXCLUDED.receipt_series,
                    status='ACTIVE', last_error=NULL, verified_at=now(), updated_at=now()
                """, store, url, cipher.encrypt(context(store), token), env, invoice, receipt);
        return account(store);
    }

    /** Vuelve a probar la conexión guardada y actualiza su estado. */
    public Account test(UUID store) {
        Active active = active(store);
        try {
            provider.test(active.credentials(), active.receiptSeries());
            db.update("UPDATE store_billing_account SET status='ACTIVE', last_error=NULL, verified_at=now() WHERE tenant_id=?", store);
        } catch (InvoiceProvider.ProviderException ex) {
            markError(store, ex.getMessage());
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        }
        return account(store);
    }

    public void disconnect(UUID store) {
        db.update("DELETE FROM store_billing_account WHERE tenant_id=?", store);
    }

    public void markError(UUID store, String message) {
        db.update("UPDATE store_billing_account SET status='ERROR', last_error=left(?,500), updated_at=now() WHERE tenant_id=?", message, store);
    }

    public Active active(UUID store) {
        Row r = row(store).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                "Conecta tu cuenta de Nubefact en Facturación antes de emitir."));
        String token;
        try {
            token = cipher.decrypt(context(store), r.token());
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "No se pudo descifrar el TOKEN de Nubefact. Vuelve a conectarlo en Facturación.");
        }
        return new Active(new InvoiceProvider.Credentials(r.apiUrl(), token), r.environment(), r.invoiceSeries(), r.receiptSeries());
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
