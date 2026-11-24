package com.saas.omnicanal.inbox.repository;

import com.saas.omnicanal.inbox.model.InboxViews.Counts;
import com.saas.omnicanal.inbox.model.InboxViews.Message;
import com.saas.omnicanal.inbox.model.InboxViews.Row;
import com.saas.omnicanal.inbox.model.InboxViews.Status;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConversationRepository {
    /** Fila de la bandeja: último mensaje (de cualquiera de los lados), nombre del contacto y si venció el SLA. */
    private static final String ROW_SELECT = """
            SELECT c.chat_id, c.status, c.ai_enabled, c.assignee, c.last_inbound_at, c.last_outbound_at, c.escalated_at,
                   c.escalation_reason, c.summary, c.handback_note, c.revision,
                   (SELECT k.full_name FROM contact k WHERE k.tenant_id = c.tenant_id AND k.merged_into IS NULL
                     AND k.phone = split_part(c.chat_id, '@', 1)) AS contact_name,
                   last.body AS last_body, last.author AS last_author,
                   (c.status = 'ESCALATED' AND c.last_inbound_at IS NOT NULL
                     AND (c.last_outbound_at IS NULL OR c.last_outbound_at < c.last_inbound_at)
                     AND c.last_inbound_at < now() - make_interval(mins => coalesce(s.sla_minutes, 15))) AS overdue
              FROM conversation c
              LEFT JOIN store_ai_settings s ON s.tenant_id = c.tenant_id
              LEFT JOIN LATERAL (
                  SELECT body, author FROM (
                      SELECT m.body, 'CUSTOMER' AS author, m.received_at AS at FROM whatsapp_inbound_message m
                       WHERE m.tenant_id = c.tenant_id AND m.chat_id = c.chat_id
                      UNION ALL
                      SELECT r.body, r.author, r.created_at FROM whatsapp_manual_reply r
                       WHERE r.tenant_id = c.tenant_id AND r.chat_id = c.chat_id
                  ) t ORDER BY at DESC LIMIT 1) last ON true
            """;

    private final JdbcTemplate db;

    public ConversationRepository(JdbcTemplate db) {
        this.db = db;
    }

    public record Full(Row row, String reason, String summary, String handbackNote, long revision) {}

    private static Row row(ResultSet rs) throws SQLException {
        String chat = rs.getString("chat_id");
        return new Row(chat, chat.contains("@s.whatsapp.net") ? chat.substring(0, chat.indexOf('@')) : null,
                rs.getString("contact_name"), Status.valueOf(rs.getString("status")), rs.getBoolean("ai_enabled"),
                rs.getString("assignee"), rs.getString("last_body"), rs.getString("last_author"),
                instant(rs.getTimestamp("last_inbound_at")), instant(rs.getTimestamp("last_outbound_at")),
                instant(rs.getTimestamp("escalated_at")), rs.getBoolean("overdue"));
    }

    /** Registra el mensaje entrante: crea la conversación o reabre una cerrada. */
    public void inbound(UUID tenant, String chat) {
        db.update("""
                INSERT INTO conversation(tenant_id, chat_id, last_inbound_at) VALUES (?,?,now())
                ON CONFLICT (tenant_id, chat_id) DO UPDATE SET last_inbound_at = now(), updated_at = now(),
                    status = CASE WHEN conversation.status = 'CLOSED' THEN 'OPEN' ELSE conversation.status END
                """, tenant, chat);
    }

    public void outbound(UUID tenant, String chat) {
        db.update("UPDATE conversation SET last_outbound_at = now(), updated_at = now() WHERE tenant_id=? AND chat_id=?", tenant, chat);
    }

    public List<Row> list(UUID tenant, Status status, String query, int limit) {
        return db.query(ROW_SELECT + """
                 WHERE c.tenant_id = ? AND c.status = ?
                   AND (? = '' OR strpos(c.chat_id, ?) > 0)
                 ORDER BY overdue DESC, c.last_inbound_at DESC NULLS LAST LIMIT ?
                """, (rs, n) -> row(rs), tenant, status.name(), query, query, limit);
    }

    public Optional<Full> find(UUID tenant, String chat) {
        return db.query(ROW_SELECT + " WHERE c.tenant_id = ? AND c.chat_id = ?", (rs, n) -> new Full(row(rs),
                rs.getString("escalation_reason"), rs.getString("summary"), rs.getString("handback_note"), rs.getLong("revision")),
                tenant, chat).stream().findFirst();
    }

    public Optional<Full> lock(UUID tenant, String chat) {
        db.query("SELECT 1 FROM conversation WHERE tenant_id=? AND chat_id=? FOR UPDATE", rs -> {}, tenant, chat);
        return find(tenant, chat);
    }

    public List<Message> messages(UUID tenant, String chat, int limit) {
        var newestFirst = db.query("""
                SELECT author, body, at, status FROM (
                    SELECT 'CUSTOMER' AS author, m.body, m.received_at AS at, NULL AS status FROM whatsapp_inbound_message m
                     WHERE m.tenant_id = ? AND m.chat_id = ?
                    UNION ALL
                    SELECT r.author, r.body, r.created_at, coalesce(rc.status, r.status) FROM whatsapp_manual_reply r
                      LEFT JOIN whatsapp_reply_receipt rc ON rc.tenant_id = r.tenant_id AND rc.chat_id = r.chat_id AND rc.provider_id = r.provider_id
                     WHERE r.tenant_id = ? AND r.chat_id = ?
                ) t ORDER BY at DESC LIMIT ?
                """, (rs, n) -> new Message(rs.getString(1), rs.getString(2), instant(rs.getTimestamp(3)), rs.getString(4)),
                tenant, chat, tenant, chat, limit);
        var chronological = new java.util.ArrayList<>(newestFirst);
        java.util.Collections.reverse(chronological);
        return chronological;
    }

    public void escalate(UUID tenant, String chat, String reason, String summary, String assignee, boolean disableAi) {
        db.update("""
                UPDATE conversation SET status = 'ESCALATED', escalated_at = coalesce(escalated_at, now()),
                       escalation_reason = left(?, 300), summary = coalesce(left(?, 2000), summary),
                       assignee = coalesce(?, assignee), ai_enabled = ai_enabled AND NOT ?, handback_note = NULL,
                       revision = revision + 1, updated_at = now()
                 WHERE tenant_id = ? AND chat_id = ?
                """, reason, summary, assignee, disableAi, tenant, chat);
    }

    public void handback(UUID tenant, String chat, String note) {
        db.update("""
                UPDATE conversation SET status = 'OPEN', escalated_at = NULL, escalation_reason = NULL, assignee = NULL,
                       ai_enabled = true, alerted_at = NULL, handback_note = left(?, 1000), revision = revision + 1, updated_at = now()
                 WHERE tenant_id = ? AND chat_id = ?
                """, note, tenant, chat);
    }

    public void setStatus(UUID tenant, String chat, Status status) {
        db.update("""
                UPDATE conversation SET status = ?, escalated_at = CASE WHEN ? = 'ESCALATED' THEN coalesce(escalated_at, now()) END,
                       revision = revision + 1, updated_at = now() WHERE tenant_id = ? AND chat_id = ?
                """, status.name(), status.name(), tenant, chat);
    }

    public void setAi(UUID tenant, String chat, boolean enabled) {
        db.update("UPDATE conversation SET ai_enabled = ?, revision = revision + 1, updated_at = now() WHERE tenant_id=? AND chat_id=?",
                enabled, tenant, chat);
    }

    /** Marca la alerta enviada; devuelve false si ya se había alertado esta escalada. */
    public boolean markAlerted(UUID tenant, String chat) {
        return db.update("UPDATE conversation SET alerted_at = now() WHERE tenant_id=? AND chat_id=? AND alerted_at IS NULL",
                tenant, chat) == 1;
    }

    public Counts counts(UUID tenant) {
        return db.queryForObject("""
                SELECT count(*) FILTER (WHERE c.status = 'OPEN'), count(*) FILTER (WHERE c.status = 'ESCALATED'),
                       count(*) FILTER (WHERE c.status = 'ESCALATED' AND c.last_inbound_at IS NOT NULL
                         AND (c.last_outbound_at IS NULL OR c.last_outbound_at < c.last_inbound_at)
                         AND c.last_inbound_at < now() - make_interval(mins => coalesce(s.sla_minutes, 15)))
                  FROM conversation c LEFT JOIN store_ai_settings s ON s.tenant_id = c.tenant_id WHERE c.tenant_id = ?
                """, (rs, n) -> new Counts(rs.getLong(1), rs.getLong(2), rs.getLong(3)), tenant);
    }

    public record Owner(String email, String storeName) {}

    public Optional<Owner> owner(UUID tenant) {
        return db.query("SELECT o.email, t.name FROM tenant_owner o JOIN tenant t ON t.id = o.tenant_id WHERE t.id = ?",
                (rs, n) -> new Owner(rs.getString(1), rs.getString(2)), tenant).stream().findFirst();
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
