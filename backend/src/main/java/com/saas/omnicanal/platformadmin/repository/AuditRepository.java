package com.saas.omnicanal.platformadmin.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditRepository {
    private final JdbcTemplate db;

    public AuditRepository(JdbcTemplate db) {
        this.db = db;
    }

    public record Entry(UUID id, String actor, String action, UUID tenant, String tenantName, String detail, Instant createdAt) {}

    public void record(String actor, String action, UUID tenant, String detail) {
        db.update("INSERT INTO platform_audit_log(actor, action, tenant_id, detail) VALUES (?, ?, ?, ?)",
                actor, action, tenant, detail == null || detail.length() <= 500 ? detail : detail.substring(0, 500));
    }

    public List<Entry> page(UUID tenant, int page, int size) {
        return db.query("""
                SELECT a.id, a.actor, a.action, a.tenant_id, t.name, a.detail, a.created_at
                FROM platform_audit_log a LEFT JOIN tenant t ON t.id = a.tenant_id
                WHERE (?::uuid IS NULL OR a.tenant_id = ?::uuid)
                ORDER BY a.created_at DESC LIMIT ? OFFSET ?
                """, (rs, row) -> new Entry(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getObject(4, UUID.class), rs.getString(5), rs.getString(6), instant(rs.getTimestamp(7))),
                tenant, tenant, size + 1, page * size);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
