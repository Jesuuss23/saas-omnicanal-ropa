package com.saas.omnicanal.payments.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OAuthStateRepository {
    private final JdbcTemplate db;

    public OAuthStateRepository(JdbcTemplate db) {
        this.db = db;
    }

    public void save(String stateHash, UUID tenant) {
        db.update("DELETE FROM payment_oauth_state WHERE expires_at < now() - interval '1 day'");
        db.update("INSERT INTO payment_oauth_state(state_hash,tenant_id,expires_at) VALUES (?,?,now()+interval '10 minutes')",
                stateHash, tenant);
    }

    /** Consume el state una sola vez; devuelve la tienda que lo generó si sigue vigente. */
    public Optional<UUID> consume(String stateHash) {
        return db.query("""
                UPDATE payment_oauth_state SET used_at=now()
                 WHERE state_hash=? AND used_at IS NULL AND expires_at>now() RETURNING tenant_id
                """, (rs, n) -> rs.getObject(1, UUID.class), stateHash).stream().findFirst();
    }
}
