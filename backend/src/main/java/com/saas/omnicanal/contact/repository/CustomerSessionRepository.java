package com.saas.omnicanal.contact.repository;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CustomerSessionRepository {
    private final JdbcTemplate db;

    public CustomerSessionRepository(JdbcTemplate db) {
        this.db = db;
    }

    public void insert(String tokenHash, UUID tenant, UUID contact, Instant expiresAt) {
        db.update("INSERT INTO customer_session(token_hash, tenant_id, contact_id, expires_at) VALUES (?,?,?,?)",
                tokenHash, tenant, contact, Timestamp.from(expiresAt));
    }

    /** Contacto vigente de la sesión; si el contacto fue fusionado, sigue al sobreviviente. */
    public Optional<UUID> contact(String tokenHash, UUID tenant) {
        return db.queryForList("""
                SELECT coalesce(c.merged_into, c.id) FROM customer_session s
                  JOIN contact c ON c.tenant_id = s.tenant_id AND c.id = s.contact_id
                 WHERE s.token_hash = ? AND s.tenant_id = ? AND s.expires_at > now()
                """, UUID.class, tokenHash, tenant).stream().findFirst();
    }

    public void delete(String tokenHash) {
        db.update("DELETE FROM customer_session WHERE token_hash=?", tokenHash);
    }

    public int purgeExpired() {
        return db.update("DELETE FROM customer_session WHERE expires_at < now()");
    }
}
