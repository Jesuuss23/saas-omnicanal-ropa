package com.saas.omnicanal.assistant.repository;

import com.saas.omnicanal.assistant.model.AssistantModels.Settings;
import com.saas.omnicanal.assistant.model.AssistantModels.StoreFacts;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AssistantRepository {
    public static final String PLATFORM_FLAG = "ai.replies";

    private final JdbcTemplate db;

    public AssistantRepository(JdbcTemplate db) {
        this.db = db;
    }

    public Settings settings(UUID tenant) {
        return db.query("SELECT enabled, instructions, sla_minutes, revision FROM store_ai_settings WHERE tenant_id=?",
                (rs, n) -> new Settings(rs.getBoolean(1), rs.getString(2), rs.getInt(3), rs.getLong(4)), tenant)
                .stream().findFirst().orElse(new Settings(false, "", 15, 0));
    }

    /** Guarda si la revisión coincide (0 = todavía no existe la fila). */
    public boolean saveSettings(UUID tenant, long revision, boolean enabled, String instructions, int sla) {
        if (revision == 0) {
            return db.update("""
                    INSERT INTO store_ai_settings(tenant_id, enabled, instructions, sla_minutes) VALUES (?,?,?,?)
                    ON CONFLICT DO NOTHING
                    """, tenant, enabled, instructions, sla) == 1;
        }
        return db.update("""
                UPDATE store_ai_settings SET enabled=?, instructions=?, sla_minutes=?, revision=revision+1, updated_at=now()
                 WHERE tenant_id=? AND revision=?
                """, enabled, instructions, sla, tenant, revision) == 1;
    }

    public boolean platformEnabled() {
        return Boolean.TRUE.equals(db.query("SELECT enabled FROM platform_flag WHERE key=?", (rs, n) -> rs.getBoolean(1), PLATFORM_FLAG)
                .stream().findFirst().orElse(true));
    }

    public void setPlatformEnabled(boolean enabled, String by) {
        db.update("""
                INSERT INTO platform_flag(key, enabled, updated_by) VALUES (?,?,?)
                ON CONFLICT (key) DO UPDATE SET enabled=excluded.enabled, updated_by=excluded.updated_by, updated_at=now()
                """, PLATFORM_FLAG, enabled, by);
    }

    /** Reserva el mensaje para la IA; false si ya se procesó (webhook repetido). */
    public boolean claim(UUID tenant, String chat, String messageId) {
        return db.update("INSERT INTO ai_reply(tenant_id, chat_id, message_id, status) VALUES (?,?,?,'PENDING') ON CONFLICT DO NOTHING",
                tenant, chat, messageId) == 1;
    }

    public void finish(UUID tenant, String chat, String messageId, String status, String skipReason, String reply,
                       String tools, Integer latencyMs, String error) {
        db.update("""
                UPDATE ai_reply SET status=?, skip_reason=?, reply=left(?, 4000), tools_used=left(?, 300), latency_ms=?, error=left(?, 500)
                 WHERE tenant_id=? AND chat_id=? AND message_id=?
                """, status, skipReason, reply, tools, latencyMs, error, tenant, chat, messageId);
    }

    public long sentThisMonth(UUID tenant) {
        Long n = db.queryForObject("""
                SELECT count(*) FROM ai_reply WHERE tenant_id=? AND status='SENT'
                   AND created_at >= date_trunc('month', now() AT TIME ZONE 'America/Lima') AT TIME ZONE 'America/Lima'
                """, Long.class, tenant);
        return n == null ? 0 : n;
    }

    public Optional<StoreFacts> facts(UUID tenant, String baseUrl) {
        return db.query("""
                SELECT name, CASE WHEN pickup_enabled THEN pickup_address END, CASE WHEN pickup_enabled THEN pickup_hours END,
                       free_delivery_minimum, delivery_per_km IS NOT NULL FROM tenant WHERE id=?
                """, (rs, n) -> new StoreFacts(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                rs.getBoolean(5), baseUrl + "/tienda/" + tenant), tenant).stream().findFirst();
    }
}
