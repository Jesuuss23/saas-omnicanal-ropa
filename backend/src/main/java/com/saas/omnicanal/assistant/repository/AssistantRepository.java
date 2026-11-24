package com.saas.omnicanal.assistant.repository;

import com.saas.omnicanal.assistant.model.AssistantModels.Settings;
import com.saas.omnicanal.assistant.model.AssistantModels.StoreFacts;
import java.util.List;
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

    public record Pending(String messageId, String body) {}

    /**
     * Toma en un solo UPDATE todos los mensajes en espera del chat (PENDING → WORKING) y los devuelve en orden de llegada.
     * Si dos hilos (o dos instancias) intentan el mismo chat a la vez, PostgreSQL entrega cada fila a uno solo.
     */
    public List<Pending> takeBatch(UUID tenant, String chat) {
        return db.query("""
                WITH taken AS (
                    UPDATE ai_reply SET status = 'WORKING'
                     WHERE tenant_id = ? AND chat_id = ? AND status = 'PENDING'
                    RETURNING message_id, created_at)
                SELECT t.message_id, coalesce(m.body, '') FROM taken t
                  LEFT JOIN whatsapp_inbound_message m ON m.tenant_id = ? AND m.chat_id = ? AND m.message_id = t.message_id
                 ORDER BY coalesce(m.received_at, t.created_at), t.created_at, t.message_id
                """, (rs, n) -> new Pending(rs.getString(1), rs.getString(2)), tenant, chat, tenant, chat);
    }

    /** Mensajes respondidos junto con {@code lead}: el resultado (respuesta, latencia, error) queda en la fila de lead. */
    public void markBatched(UUID tenant, String chat, List<String> messageIds, String lead) {
        if (messageIds.isEmpty()) return;
        db.update("UPDATE ai_reply SET status = 'BATCHED', batch_of = ? WHERE tenant_id = ? AND chat_id = ? AND message_id = ANY(?)",
                lead, tenant, chat, messageIds.toArray(String[]::new));
    }

    public record ChatKey(UUID tenant, String chat) {}

    /** Chats con mensajes en espera más antiguos que {@code seconds} (por ejemplo, tras reiniciar el servidor). */
    public List<ChatKey> stalePending(int seconds) {
        return db.query("""
                SELECT DISTINCT tenant_id, chat_id FROM ai_reply
                 WHERE status = 'PENDING' AND created_at < now() - make_interval(secs => ?) LIMIT 200
                """, (rs, n) -> new ChatKey(rs.getObject(1, UUID.class), rs.getString(2)), seconds);
    }

    /** Lotes que quedaron a medias (el servidor se cayó durante la llamada al modelo): no se reintentan para no duplicar. */
    public int failStuckWorking(int minutes) {
        return db.update("""
                UPDATE ai_reply SET status = 'FAILED', error = 'El servidor se detuvo mientras respondía'
                 WHERE status = 'WORKING' AND created_at < now() - make_interval(mins => ?)
                """, minutes);
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
