package com.saas.omnicanal.fiscal.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FiscalProductionRepository {
    private final JdbcTemplate db;

    public FiscalProductionRepository(JdbcTemplate db) {
        this.db = db;
    }

    public record Issuer(String ruc, String legalName) {}

    public Optional<Issuer> issuer(UUID tenant) {
        return db.query("SELECT ruc, legal_name FROM tenant_fiscal_issuer WHERE tenant_id=?",
                (rs, n) -> new Issuer(rs.getString(1), rs.getString(2)), tenant).stream().findFirst();
    }

    public record Row(String solUser, boolean hasSolPassword, boolean hasCertificate, String certificateSubject,
                      Instant certificateNotAfter, String invoiceSeries, String receiptSeries, String status,
                      Instant activatedAt, String activatedBy, long revision) {}

    public Optional<Row> find(UUID tenant) {
        return db.query("""
                SELECT sol_user, sol_password_ciphertext IS NOT NULL, certificate_ciphertext IS NOT NULL, certificate_subject,
                       certificate_not_after, invoice_series, receipt_series, status, activated_at, activated_by, revision
                  FROM fiscal_production_setup WHERE tenant_id=?
                """, (rs, n) -> new Row(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3), rs.getString(4), instant(rs.getTimestamp(5)),
                rs.getString(6), rs.getString(7), rs.getString(8), instant(rs.getTimestamp(9)), rs.getString(10), rs.getLong(11)), tenant)
                .stream().findFirst();
    }

    /** Crea la fila vacía si no existe y devuelve su revisión actual. */
    public long ensure(UUID tenant) {
        db.update("INSERT INTO fiscal_production_setup(tenant_id) VALUES (?) ON CONFLICT DO NOTHING", tenant);
        return db.queryForObject("SELECT revision FROM fiscal_production_setup WHERE tenant_id=?", Long.class, tenant);
    }

    /** Cambiar credenciales, certificado o series vuelve el alta a borrador: hay que activarla de nuevo. */
    public boolean saveSol(UUID tenant, long revision, String user, byte[] password) {
        return db.update("""
                UPDATE fiscal_production_setup SET sol_user=?, sol_password_ciphertext=?, status='DRAFT', activated_at=NULL,
                       activated_by=NULL, revision=revision+1, updated_at=now() WHERE tenant_id=? AND revision=?
                """, user, password, tenant, revision) == 1;
    }

    public boolean saveCertificate(UUID tenant, long revision, byte[] certificate, byte[] password, String subject, Instant notAfter) {
        return db.update("""
                UPDATE fiscal_production_setup SET certificate_ciphertext=?, certificate_password_ciphertext=?, certificate_subject=?,
                       certificate_not_after=?, status='DRAFT', activated_at=NULL, activated_by=NULL, revision=revision+1, updated_at=now()
                 WHERE tenant_id=? AND revision=?
                """, certificate, password, subject, Timestamp.from(notAfter), tenant, revision) == 1;
    }

    public boolean saveSeries(UUID tenant, long revision, String invoice, String receipt) {
        return db.update("""
                UPDATE fiscal_production_setup SET invoice_series=?, receipt_series=?, status='DRAFT', activated_at=NULL,
                       activated_by=NULL, revision=revision+1, updated_at=now() WHERE tenant_id=? AND revision=?
                """, invoice, receipt, tenant, revision) == 1;
    }

    public boolean setStatus(UUID tenant, long revision, String status, String by) {
        return db.update("""
                UPDATE fiscal_production_setup SET status=?, activated_at=CASE WHEN ?='ACTIVE' THEN now() END,
                       activated_by=CASE WHEN ?='ACTIVE' THEN ? END, revision=revision+1, updated_at=now()
                 WHERE tenant_id=? AND revision=?
                """, status, status, status, by, tenant, revision) == 1;
    }

    // --- Conciliación automática ---

    public record Due(UUID tenant, UUID sale) {}

    /**
     * Toma comprobantes en revisión cuya próxima consulta ya venció y agenda la siguiente con espera creciente
     * (2, 4, 8… minutos, tope de 6 horas, máximo 10 intentos). Se agenda antes de consultar: un fallo no repite en bucle.
     */
    public List<Due> claimDue(int limit) {
        return db.query("""
                WITH due AS (
                    SELECT f.tenant_id, f.sale_id FROM pos_fiscal_request f
                      JOIN beta_pos_document_number n ON n.tenant_id = f.tenant_id AND n.sale_id = f.sale_id
                     WHERE f.status = 'BETA_REVIEW' AND n.submitted_at IS NOT NULL AND f.reconcile_attempts < 10
                       AND coalesce(f.next_reconcile_at, n.submitted_at + interval '2 minutes') <= now()
                     ORDER BY coalesce(f.next_reconcile_at, n.submitted_at) LIMIT ? FOR UPDATE OF f SKIP LOCKED)
                UPDATE pos_fiscal_request f SET reconcile_attempts = f.reconcile_attempts + 1,
                       next_reconcile_at = now() + least(power(2, f.reconcile_attempts + 1) * interval '1 minute', interval '6 hours')
                  FROM due WHERE f.tenant_id = due.tenant_id AND f.sale_id = due.sale_id
                RETURNING f.tenant_id, f.sale_id
                """, (rs, n) -> new Due(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), limit);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
