package com.saas.omnicanal.onboarding.repository;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SignupRepository {
    private final JdbcTemplate db;

    public SignupRepository(JdbcTemplate db) {
        this.db = db;
    }

    public record Pending(UUID id, String email, String storeName, String ownerName, String phone, String plan) {}

    public int recentRequests(String email, Duration window) {
        Integer count = db.queryForObject("""
                SELECT count(*) FROM signup_request WHERE lower(email) = lower(?) AND created_at > now() - make_interval(secs => ?)
                """, Integer.class, email, window.toSeconds());
        return count == null ? 0 : count;
    }

    public void create(String email, String storeName, String ownerName, String phone, String plan, String tokenHash, Duration ttl) {
        db.update("""
                INSERT INTO signup_request(email, store_name, owner_name, phone, plan_code, token_hash, expires_at)
                VALUES (?, ?, ?, ?, (SELECT code FROM saas_plan WHERE code = ? AND public), ?, now() + make_interval(secs => ?))
                """, email, storeName, ownerName, phone, plan, tokenHash, ttl.toSeconds());
    }

    /** Solicitud vigente y sin confirmar; con lock para que dos confirmaciones no creen dos tiendas. */
    public Optional<Pending> findOpen(String tokenHash, boolean lock) {
        return db.query("""
                SELECT id, email, store_name, owner_name, phone, plan_code FROM signup_request
                WHERE token_hash = ? AND confirmed_at IS NULL AND expires_at > now()
                """ + (lock ? " FOR UPDATE" : ""), (rs, row) -> new Pending(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)), tokenHash).stream().findFirst();
    }

    public void markConfirmed(UUID request, UUID tenant) {
        db.update("UPDATE signup_request SET confirmed_at = now(), tenant_id = ? WHERE id = ?", tenant, request);
    }

    public void createStore(UUID tenant, String name, String email, String passwordHash, String fullName, String phone) {
        db.update("INSERT INTO tenant(id, name) VALUES (?, ?)", tenant, name);
        db.update("INSERT INTO tenant_owner(tenant_id, email, password_hash, full_name, phone) VALUES (?, ?, ?, ?, ?)",
                tenant, email, passwordHash, fullName, phone);
    }
}
