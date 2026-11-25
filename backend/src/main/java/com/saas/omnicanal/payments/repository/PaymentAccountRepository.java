package com.saas.omnicanal.payments.repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Cuenta Culqi de cada tienda; la llave secreta se guarda cifrada en access_token_ciphertext. */
@Repository
public class PaymentAccountRepository {
    private final JdbcTemplate db;

    public PaymentAccountRepository(JdbcTemplate db) {
        this.db = db;
    }

    public record Row(UUID tenantId, String publicKey, byte[] secretKey, boolean liveMode, String status, String lastError,
                      OffsetDateTime linkedAt) {
        @Override public String toString() { return "Row[tenant=" + tenantId + ", liveMode=" + liveMode + "]"; }
    }

    public Optional<Row> find(UUID tenant) {
        return db.query("""
                SELECT tenant_id,public_key,access_token_ciphertext,live_mode,status,last_error,linked_at
                  FROM store_payment_account WHERE tenant_id=? AND provider='CULQI'
                """, (rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getBytes(3), rs.getBoolean(4),
                rs.getString(5), rs.getString(6), rs.getObject(7, OffsetDateTime.class)), tenant).stream().findFirst();
    }

    public void upsert(UUID tenant, String publicKey, byte[] secretKey, boolean liveMode) {
        db.update("""
                INSERT INTO store_payment_account(tenant_id,provider,public_key,access_token_ciphertext,live_mode,status)
                VALUES (?,'CULQI',?,?,?,'ACTIVE')
                ON CONFLICT (tenant_id) DO UPDATE SET provider='CULQI', public_key=excluded.public_key,
                    access_token_ciphertext=excluded.access_token_ciphertext, live_mode=excluded.live_mode,
                    status='ACTIVE', last_error=NULL, linked_at=now(), updated_at=now()
                """, tenant, publicKey, secretKey, liveMode);
    }

    public void markError(UUID tenant, String error) {
        db.update("UPDATE store_payment_account SET status='ERROR', last_error=left(?,500), updated_at=now() WHERE tenant_id=?",
                error, tenant);
    }

    public void delete(UUID tenant) {
        db.update("DELETE FROM store_payment_account WHERE tenant_id=?", tenant);
    }
}
