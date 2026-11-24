package com.saas.omnicanal.fiscal;

import static org.junit.jupiter.api.Assertions.*;

import com.saas.omnicanal.fiscal.service.FiscalProductionService;
import com.saas.omnicanal.fiscal.service.FiscalReconciler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Alta fiscal para producción (4 pasos, activación explícita) y conciliación automática sin reenvíos. */
@SpringBootTest
class FiscalProductionTests {
    @Autowired JdbcTemplate db;
    @Autowired FiscalProductionService production;
    @Autowired FiscalReconciler reconciler;
    @TempDir Path dir;

    @Test
    void fourStepsThenExplicitActivation() throws Exception {
        UUID store = UUID.randomUUID();
        db.update("INSERT INTO tenant(id,name) VALUES (?, 'Fiscal')", store);
        db.update("INSERT INTO tenant_subscription(tenant_id,plan_code,status,trial_ends_at) VALUES (?, 'TRIAL', 'TRIAL', now() + interval '5 days')", store);
        assertFalse(production.setup(store).issuerReady());
        assertEquals(409, code(() -> production.saveSol(store, 0, "MODDATOS", "clave")), "sin emisor no hay paso 2");

        String ruc = "20" + String.format("%09d", Math.abs(store.getMostSignificantBits() % 1_000_000_000L));
        db.update("INSERT INTO tenant_fiscal_issuer(tenant_id,ruc,legal_name,address,ubigeo,revision) VALUES (?,?,'Tienda SAC','Av. Lima 1','150101',1)", store, ruc);

        var s = production.saveSol(store, 0, "moddatos", "secreta-sol");
        assertEquals("MODDATOS", s.solUser());
        assertTrue(s.solReady());
        byte[] stored = db.queryForObject("SELECT sol_password_ciphertext FROM fiscal_production_setup WHERE tenant_id=?", byte[].class, store);
        assertFalse(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1).contains("secreta-sol"), "la clave SOL se guarda cifrada");

        String pfx = Base64.getEncoder().encodeToString(certificate());
        long revision = s.revision();
        assertEquals(400, code(() -> production.saveCertificate(store, revision, pfx, "otra")), "clave del certificado incorrecta");
        assertEquals(400, code(() -> production.saveCertificate(store, revision, "bm8gZXMgdW4gcGZ4", "clave123")));
        s = production.saveCertificate(store, revision, pfx, "clave123");
        assertTrue(s.certificateReady());
        assertTrue(s.certificateSubject().contains("Tienda Prueba SAC"), s.certificateSubject());

        long stale = revision;
        assertEquals(409, code(() -> production.saveSeries(store, stale, "F001", "B001")), "revisión vieja");
        assertEquals(400, code(() -> production.saveSeries(store, 0, "B001", "F001")));
        s = production.saveSeries(store, s.revision(), "f001", "b001");
        assertEquals("F001", s.invoiceSeries());

        long ready = s.revision();
        assertEquals(402, code(() -> production.activate(store, ready, ruc, "dueno@test")), "la prueba gratis no incluye producción");
        db.update("INSERT INTO tenant_entitlement_override(tenant_id,entitlement_key,enabled,limit_value,reason) VALUES (?, 'fiscal.production', true, NULL, 'prueba')", store);
        assertEquals(400, code(() -> production.activate(store, ready, "20000000000", "dueno@test")), "hay que escribir el RUC correcto");
        s = production.activate(store, ready, ruc, "dueno@test");
        assertEquals("ACTIVE", s.status());
        assertEquals("dueno@test", s.activatedBy());

        // Cambiar un dato sensible vuelve a borrador: la activación nunca queda con datos que el dueño no confirmó.
        s = production.saveSeries(store, s.revision(), "F002", "B002");
        assertEquals("DRAFT", s.status());
        assertNull(s.activatedAt());
    }

    @Test
    void reviewDocumentsAreRetriedWithBackoffWithoutResending() {
        UUID store = UUID.randomUUID(), sale = UUID.randomUUID();
        String ruc = "10" + String.format("%09d", Math.abs(store.getLeastSignificantBits() % 1_000_000_000L));
        db.update("INSERT INTO tenant(id,name) VALUES (?, 'Revisión')", store);
        db.update("INSERT INTO pos_sale(tenant_id,id,method,total,request_signature) VALUES (?,?,'CASH',10,'x')", store, sale);
        db.update("INSERT INTO pos_fiscal_request(tenant_id,sale_id,kind,status) VALUES (?,?,'BOLETA','BETA_REVIEW')", store, sale);
        db.update("INSERT INTO beta_document_counter(issuer_ruc,series,last_number) VALUES (?, 'BT01', 1)", ruc);
        db.update("INSERT INTO beta_pos_document_number(tenant_id,sale_id,issuer_ruc,series,number,submitted_at) VALUES (?,?,?,'BT01',1,now() - interval '10 minutes')",
                store, sale, ruc);

        reconciler.runOnce();
        assertEquals(1, attempts(store, sale));
        assertEquals("BETA_REVIEW", db.queryForObject("SELECT status FROM pos_fiscal_request WHERE tenant_id=? AND sale_id=?", String.class, store, sale));
        assertTrue(db.queryForObject("SELECT next_reconcile_at > now() + interval '1 minute' FROM pos_fiscal_request WHERE tenant_id=? AND sale_id=?",
                Boolean.class, store, sale));
        reconciler.runOnce();
        assertEquals(1, attempts(store, sale), "espera antes de volver a consultar");
    }

    private int attempts(UUID store, UUID sale) {
        return db.queryForObject("SELECT reconcile_attempts FROM pos_fiscal_request WHERE tenant_id=? AND sale_id=?", Integer.class, store, sale);
    }

    private byte[] certificate() throws Exception {
        Path file = dir.resolve("cert.p12");
        var keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        var p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "sunat", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=Tienda Prueba SAC, C=PE", "-validity", "365", "-storetype", "PKCS12",
                "-keystore", file.toString(), "-storepass", "clave123", "-keypass", "clave123").redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), out);
        return Files.readAllBytes(file);
    }

    private static int code(org.junit.jupiter.api.function.Executable action) {
        return assertThrows(ResponseStatusException.class, action).getStatusCode().value();
    }
}
