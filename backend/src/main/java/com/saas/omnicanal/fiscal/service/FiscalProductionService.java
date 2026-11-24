package com.saas.omnicanal.fiscal.service;

import com.saas.omnicanal.fiscal.model.ProductionSetup;
import com.saas.omnicanal.fiscal.repository.FiscalProductionRepository;
import com.saas.omnicanal.shared.crypto.SecretCipher;
import com.saas.omnicanal.subscription.service.EntitlementService;
import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Alta fiscal para producción: usuario SOL, certificado digital y series, cifrados con {@link SecretCipher}.
 * La activación es explícita (el dueño escribe su RUC) y exige la capacidad {@code fiscal.production} del plan.
 */
@Service
public class FiscalProductionService {
    private static final int MAX_CERTIFICATE_BYTES = 64 * 1024;
    private final FiscalProductionRepository repo;
    private final SecretCipher cipher;
    private final EntitlementService entitlements;

    public FiscalProductionService(FiscalProductionRepository repo, SecretCipher cipher, EntitlementService entitlements) {
        this.repo = repo;
        this.cipher = cipher;
        this.entitlements = entitlements;
    }

    static String solContext(UUID tenant) { return "fiscal/sol-password/" + tenant; }
    static String certificateContext(UUID tenant) { return "fiscal/certificate/" + tenant; }
    static String certificatePasswordContext(UUID tenant) { return "fiscal/certificate-password/" + tenant; }

    @Transactional(readOnly = true)
    public ProductionSetup setup(UUID tenant) {
        var issuer = repo.issuer(tenant).orElse(null);
        var row = repo.find(tenant).orElse(null);
        boolean plan = entitlements.enabled(tenant, "fiscal.production");
        if (row == null)
            return new ProductionSetup(issuer == null ? null : issuer.ruc(), issuer == null ? null : issuer.legalName(), issuer != null,
                    null, false, null, null, false, null, null, false, "DRAFT", null, null, plan, 0);
        boolean certOk = row.hasCertificate() && row.certificateNotAfter() != null && row.certificateNotAfter().isAfter(Instant.now());
        return new ProductionSetup(issuer.ruc(), issuer.legalName(), true, row.solUser(), row.hasSolPassword(), row.certificateSubject(),
                row.certificateNotAfter(), certOk, row.invoiceSeries(), row.receiptSeries(),
                row.invoiceSeries() != null && row.receiptSeries() != null, row.status(), row.activatedAt(), row.activatedBy(), plan,
                row.revision());
    }

    @Transactional
    public ProductionSetup saveSol(UUID tenant, long revision, String user, String password) {
        requireCipher();
        String u = user == null ? "" : user.strip().toUpperCase();
        if (!u.matches("[A-Z0-9]{3,20}")) throw bad("El usuario SOL tiene de 3 a 20 letras o números (sin el RUC)");
        if (password == null || password.isBlank() || password.length() > 50) throw bad("Escribe la clave SOL");
        long current = start(tenant, revision);
        check(repo.saveSol(tenant, current, u, cipher.encrypt(solContext(tenant), password)));
        return setup(tenant);
    }

    /** Valida que el .pfx abra con su clave, que tenga llave privada y que no esté vencido. */
    @Transactional
    public ProductionSetup saveCertificate(UUID tenant, long revision, String base64, String password) {
        requireCipher();
        byte[] pfx;
        try {
            pfx = Base64.getDecoder().decode(base64 == null ? "" : base64.replaceAll("\\s+", ""));
        } catch (IllegalArgumentException e) {
            throw bad("El archivo del certificado no es válido");
        }
        if (pfx.length == 0 || pfx.length > MAX_CERTIFICATE_BYTES) throw bad("Sube el certificado .pfx o .p12 (máximo 64 KB)");
        if (password == null || password.length() > 100) throw bad("Escribe la clave del certificado");
        X509Certificate cert = read(pfx, password);
        if (cert.getNotAfter().toInstant().isBefore(Instant.now())) throw bad("El certificado está vencido");
        long current = start(tenant, revision);
        String subject = cert.getSubjectX500Principal().getName();
        check(repo.saveCertificate(tenant, current, cipher.encrypt(certificateContext(tenant), Base64.getEncoder().encodeToString(pfx)),
                cipher.encrypt(certificatePasswordContext(tenant), password),
                subject.length() > 300 ? subject.substring(0, 300) : subject, cert.getNotAfter().toInstant()));
        return setup(tenant);
    }

    @Transactional
    public ProductionSetup saveSeries(UUID tenant, long revision, String invoice, String receipt) {
        String f = invoice == null ? "" : invoice.strip().toUpperCase();
        String b = receipt == null ? "" : receipt.strip().toUpperCase();
        if (!f.matches("F[A-Z0-9]{3}") || !b.matches("B[A-Z0-9]{3}"))
            throw bad("Las series tienen 4 caracteres: facturas empiezan con F (F001) y boletas con B (B001)");
        long current = start(tenant, revision);
        check(repo.saveSeries(tenant, current, f, b));
        return setup(tenant);
    }

    @Transactional
    public ProductionSetup activate(UUID tenant, long revision, String typedRuc, String by) {
        entitlements.requireEnabled(tenant, "fiscal.production");
        var s = setup(tenant);
        if (!s.issuerReady() || !s.solReady() || !s.certificateReady() || !s.seriesReady())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Completa los cuatro pasos antes de activar");
        if (typedRuc == null || !typedRuc.strip().equals(s.issuerRuc()))
            throw bad("Escribe el RUC del emisor para confirmar");
        check(repo.setStatus(tenant, revision, "ACTIVE", by));
        return setup(tenant);
    }

    @Transactional
    public ProductionSetup deactivate(UUID tenant, long revision) {
        check(repo.setStatus(tenant, revision, "DISABLED", null));
        return setup(tenant);
    }

    private long start(UUID tenant, long revision) {
        if (repo.issuer(tenant).isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Primero guarda los datos del emisor (paso 1)");
        long current = repo.ensure(tenant);
        if (revision != 0 && revision != current) throw conflict();
        return current;
    }

    private static X509Certificate read(byte[] pfx, String password) {
        try {
            var store = KeyStore.getInstance("PKCS12");
            store.load(new ByteArrayInputStream(pfx), password.toCharArray());
            for (String alias : Collections.list(store.aliases())) {
                if (store.isKeyEntry(alias) && store.getCertificate(alias) instanceof X509Certificate x) return x;
            }
        } catch (Exception e) {
            throw bad("No se pudo abrir el certificado: revisa el archivo y la clave");
        }
        throw bad("El certificado no incluye la llave privada");
    }

    private void requireCipher() {
        if (!cipher.configured())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "El servidor no tiene SECRETS_ENCRYPTION_KEY configurada");
    }

    private static void check(boolean ok) {
        if (!ok) throw conflict();
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "La configuración cambió; recarga la página");
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
