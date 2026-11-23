package com.saas.omnicanal.team.repository;

import com.saas.omnicanal.team.model.Member;
import com.saas.omnicanal.team.model.StoreRole;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class MemberRepository {
    private static final RowMapper<Member> ROW = (rs, n) -> new Member(rs.getObject("id", UUID.class), rs.getString("email"),
            rs.getString("full_name"), StoreRole.valueOf(rs.getString("role")), rs.getBoolean("active"),
            rs.getString("password_hash") != null,
            rs.getTimestamp("invitation_expires_at") != null && rs.getTimestamp("invitation_expires_at").toInstant().isAfter(Instant.now()),
            instant(rs.getTimestamp("last_login_at")), instant(rs.getTimestamp("created_at")));

    private final JdbcTemplate db;

    public MemberRepository(JdbcTemplate db) {
        this.db = db;
    }

    public record LoginCandidate(UUID tenant, String storeName, UUID memberId, String email, StoreRole role, String hash) {}

    public List<Member> list(UUID tenant) {
        return db.query("SELECT * FROM store_member WHERE tenant_id=? ORDER BY active DESC, full_name, id", ROW, tenant);
    }

    public Optional<Member> find(UUID tenant, UUID id) {
        return db.query("SELECT * FROM store_member WHERE tenant_id=? AND id=?", ROW, tenant, id).stream().findFirst();
    }

    public long countActive(UUID tenant) {
        Long n = db.queryForObject("SELECT count(*) FROM store_member WHERE tenant_id=? AND active", Long.class, tenant);
        return n == null ? 0 : n;
    }

    public boolean emailTaken(UUID tenant, String email) {
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM store_member WHERE tenant_id=? AND lower(email)=lower(?))
                    OR EXISTS(SELECT 1 FROM tenant_owner WHERE tenant_id=? AND lower(email)=lower(?))
                """, Boolean.class, tenant, email, tenant, email));
    }

    public UUID insert(UUID tenant, String email, String name, StoreRole role, String invitationHash) {
        return db.queryForObject("""
                INSERT INTO store_member(tenant_id, email, full_name, role, invitation_hash, invitation_expires_at)
                VALUES (?,?,?,?,?, now() + interval '72 hours') RETURNING id
                """, UUID.class, tenant, email, name, role.name(), invitationHash);
    }

    public boolean renewInvitation(UUID tenant, UUID id, String invitationHash) {
        return db.update("""
                UPDATE store_member SET invitation_hash=?, invitation_expires_at=now() + interval '72 hours'
                 WHERE tenant_id=? AND id=? AND password_hash IS NULL AND active
                """, invitationHash, tenant, id) == 1;
    }

    public boolean update(UUID tenant, UUID id, StoreRole role, boolean active) {
        return db.update("UPDATE store_member SET role=?, active=? WHERE tenant_id=? AND id=?", role.name(), active, tenant, id) == 1;
    }

    public boolean activate(UUID tenant, UUID id, String invitationHash, String passwordHash) {
        return db.update("""
                UPDATE store_member SET password_hash=?, invitation_hash=NULL, invitation_expires_at=NULL
                 WHERE tenant_id=? AND id=? AND invitation_hash=? AND invitation_expires_at > now() AND password_hash IS NULL AND active
                """, passwordHash, tenant, id, invitationHash) == 1;
    }

    public Optional<String> storeName(UUID tenant) {
        return db.queryForList("SELECT name FROM tenant WHERE id=?", String.class, tenant).stream().findFirst();
    }

    /** Miembros activos con acceso que coinciden con el correo (y opcionalmente con la tienda elegida). */
    public List<LoginCandidate> loginCandidates(String email, UUID store) {
        return db.query("""
                SELECT m.tenant_id, t.name, m.id, m.email, m.role, m.password_hash FROM store_member m JOIN tenant t ON t.id = m.tenant_id
                 WHERE m.active AND m.password_hash IS NOT NULL AND lower(m.email) = ? AND (?::uuid IS NULL OR m.tenant_id = ?::uuid)
                """, (rs, n) -> new LoginCandidate(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                rs.getString(4), StoreRole.valueOf(rs.getString(5)), rs.getString(6)), email, store, store);
    }

    public void touchLogin(UUID id) {
        db.update("UPDATE store_member SET last_login_at=now() WHERE id=?", id);
    }

    public boolean isActive(UUID tenant, UUID id) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM store_member WHERE tenant_id=? AND id=? AND active)",
                Boolean.class, tenant, id));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
