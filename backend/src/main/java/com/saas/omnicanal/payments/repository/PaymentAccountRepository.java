package com.saas.omnicanal.payments.repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentAccountRepository {
    private final JdbcTemplate db;

    public PaymentAccountRepository(JdbcTemplate db) {
        this.db = db;
    }

    public record Row(UUID tenantId, long providerUserId, String publicKey, byte[] accessToken, byte[] refreshToken,
                      OffsetDateTime tokenExpiresAt, boolean liveMode, String status, String lastError,
                      OffsetDateTime linkedAt) {}

    public Optional<Row> find(UUID tenant) {
        return db.query("""
                SELECT tenant_id,provider_user_id,public_key,access_token_ciphertext,refresh_token_ciphertext,
                       token_expires_at,live_mode,status,last_error,linked_at
                  FROM store_payment_account WHERE tenant_id=? AND provider='MERCADOPAGO'
                """, (rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3), rs.getBytes(4),
                rs.getBytes(5), rs.getObject(6, OffsetDateTime.class), rs.getBoolean(7), rs.getString(8),
                rs.getString(9), rs.getObject(10, OffsetDateTime.class)), tenant).stream().findFirst();
    }

    public void upsert(UUID tenant, long userId, String publicKey, byte[] accessToken, byte[] refreshToken,
                       OffsetDateTime expiresAt, boolean liveMode) {
        db.update("""
                INSERT INTO store_payment_account(tenant_id,provider_user_id,public_key,access_token_ciphertext,
                    refresh_token_ciphertext,token_expires_at,live_mode)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT (tenant_id) DO UPDATE SET provider_user_id=excluded.provider_user_id,
                    public_key=excluded.public_key, access_token_ciphertext=excluded.access_token_ciphertext,
                    refresh_token_ciphertext=excluded.refresh_token_ciphertext, token_expires_at=excluded.token_expires_at,
                    live_mode=excluded.live_mode, status='ACTIVE', last_error=NULL, updated_at=now()
                """, tenant, userId, publicKey, accessToken, refreshToken, expiresAt, liveMode);
    }

    public void markError(UUID tenant, String error) {
        db.update("UPDATE store_payment_account SET status='ERROR', last_error=left(?,500), updated_at=now() WHERE tenant_id=?",
                error, tenant);
    }

    public void delete(UUID tenant) {
        db.update("DELETE FROM store_payment_account WHERE tenant_id=?", tenant);
    }
}
