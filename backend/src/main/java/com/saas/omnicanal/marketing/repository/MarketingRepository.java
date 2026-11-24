package com.saas.omnicanal.marketing.repository;

import com.saas.omnicanal.marketing.model.MarketingModels.AbandonedCart;
import com.saas.omnicanal.marketing.model.MarketingModels.AbandonedSettings;
import com.saas.omnicanal.marketing.model.MarketingModels.Campaign;
import com.saas.omnicanal.marketing.model.MarketingModels.Channel;
import com.saas.omnicanal.marketing.model.MarketingModels.Preferences;
import com.saas.omnicanal.marketing.model.MarketingModels.Recipient;
import com.saas.omnicanal.marketing.model.MarketingModels.Segment;
import com.saas.omnicanal.marketing.model.MarketingModels.Stats;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class MarketingRepository {
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public MarketingRepository(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    // --- Segmentos ---

    private record Where(String sql, List<Object> args) {}

    /** Contactos vigentes, con consentimiento y destino para el canal, que cumplen el segmento. */
    private Where segmentWhere(UUID tenant, Channel channel, Segment s) {
        var sql = new StringBuilder("""
                 FROM contact c
                WHERE c.tenant_id = ? AND c.merged_into IS NULL AND c.unsubscribed_at IS NULL
                """);
        List<Object> args = new ArrayList<>(List.of(tenant));
        sql.append(channel == Channel.EMAIL ? " AND c.email_opt_in AND c.email IS NOT NULL" : " AND c.whatsapp_opt_in AND c.phone IS NOT NULL");
        String paid = "(SELECT %s FROM web_checkout_order o WHERE o.tenant_id = c.tenant_id AND o.contact_id = c.id AND o.status = 'PAID')";
        if ("BUYERS".equals(s.buyers())) sql.append(" AND EXISTS ").append(paid.formatted("1"));
        if ("NON_BUYERS".equals(s.buyers())) sql.append(" AND NOT EXISTS ").append(paid.formatted("1"));
        if (s.lastOrderWithinDays() != null) {
            sql.append(" AND ").append(paid.formatted("max(o.created_at)")).append(" >= now() - make_interval(days => ?)");
            args.add(s.lastOrderWithinDays());
        }
        if (s.noOrderForDays() != null) {
            sql.append(" AND coalesce(").append(paid.formatted("max(o.created_at)")).append(", '-infinity') < now() - make_interval(days => ?)");
            args.add(s.noOrderForDays());
        }
        if (s.minSpent() != null) {
            sql.append(" AND coalesce(").append(paid.formatted("sum(o.merchandise_total)")).append(", 0) >= ?");
            args.add(s.minSpent());
        }
        return new Where(sql.toString(), args);
    }

    public long countSegment(UUID tenant, Channel channel, Segment segment) {
        var w = segmentWhere(tenant, channel, segment);
        Long n = db.queryForObject("SELECT count(*)" + w.sql(), Long.class, w.args().toArray());
        return n == null ? 0 : n;
    }

    /** Llena la cola de la campaña con el segmento de este momento (idempotente). */
    public int enqueue(UUID tenant, UUID campaign, Channel channel, Segment segment, long limit) {
        var w = segmentWhere(tenant, channel, segment);
        List<Object> args = new ArrayList<>(List.of(tenant, campaign));
        args.addAll(w.args());
        args.add(limit);
        return db.update("INSERT INTO campaign_recipient(tenant_id, campaign_id, contact_id, destination) SELECT ?, ?, c.id, "
                + (channel == Channel.EMAIL ? "c.email" : "c.phone") + w.sql() + " ORDER BY c.updated_at DESC LIMIT ? ON CONFLICT DO NOTHING",
                args.toArray());
    }

    /** Destinatarios de este mes por canal (para la cuota del plan). */
    public long sentThisMonth(UUID tenant, Channel channel) {
        Long n = db.queryForObject("""
                SELECT count(*) FROM campaign_recipient r JOIN campaign c ON c.tenant_id = r.tenant_id AND c.id = r.campaign_id
                 WHERE r.tenant_id = ? AND c.channel = ? AND r.status IN ('PENDING', 'SENT')
                   AND c.started_at >= date_trunc('month', now() AT TIME ZONE 'America/Lima') AT TIME ZONE 'America/Lima'
                """, Long.class, tenant, channel.name());
        return n == null ? 0 : n;
    }

    // --- Campañas ---

    private Campaign campaign(ResultSet rs) throws SQLException {
        Segment segment;
        try {
            segment = json.readValue(rs.getString("segment"), Segment.class);
        } catch (RuntimeException e) {
            segment = Segment.all();
        }
        return new Campaign(rs.getObject("id", UUID.class), rs.getString("name"), Channel.valueOf(rs.getString("channel")), segment,
                rs.getString("subject"), rs.getString("body"), rs.getString("template_name"), rs.getString("template_lang"),
                rs.getString("status"), rs.getString("created_by"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at")), rs.getLong("revision"),
                new Stats(rs.getLong("total"), rs.getLong("pending"), rs.getLong("sent"), rs.getLong("failed"), rs.getLong("skipped")));
    }

    private static final String CAMPAIGN_SELECT = """
            SELECT c.*, count(r.contact_id) AS total, count(*) FILTER (WHERE r.status = 'PENDING') AS pending,
                   count(*) FILTER (WHERE r.status = 'SENT') AS sent, count(*) FILTER (WHERE r.status = 'FAILED') AS failed,
                   count(*) FILTER (WHERE r.status = 'SKIPPED') AS skipped
              FROM campaign c LEFT JOIN campaign_recipient r ON r.tenant_id = c.tenant_id AND r.campaign_id = c.id
            """;

    public List<Campaign> campaigns(UUID tenant) {
        return db.query(CAMPAIGN_SELECT + " WHERE c.tenant_id = ? GROUP BY c.tenant_id, c.id ORDER BY c.created_at DESC LIMIT 50",
                (rs, n) -> campaign(rs), tenant);
    }

    public Optional<Campaign> find(UUID tenant, UUID id) {
        return db.query(CAMPAIGN_SELECT + " WHERE c.tenant_id = ? AND c.id = ? GROUP BY c.tenant_id, c.id",
                (rs, n) -> campaign(rs), tenant, id).stream().findFirst();
    }

    public UUID insert(UUID tenant, String name, Channel channel, Segment segment, String subject, String body,
                       String template, String lang, String by) {
        return db.queryForObject("""
                INSERT INTO campaign(tenant_id, name, channel, segment, subject, body, template_name, template_lang, created_by)
                VALUES (?,?,?,CAST(? AS jsonb),?,?,?,?,?) RETURNING id
                """, UUID.class, tenant, name, channel.name(), json.writeValueAsString(segment), subject, body, template, lang, by);
    }

    public boolean update(UUID tenant, UUID id, long revision, String name, Segment segment, String subject, String body,
                          String template, String lang) {
        return db.update("""
                UPDATE campaign SET name=?, segment=CAST(? AS jsonb), subject=?, body=?, template_name=?, template_lang=?,
                       revision=revision+1 WHERE tenant_id=? AND id=? AND revision=? AND status='DRAFT'
                """, name, json.writeValueAsString(segment), subject, body, template, lang, tenant, id, revision) == 1;
    }

    public boolean start(UUID tenant, UUID id, long revision) {
        return db.update("""
                UPDATE campaign SET status='SENDING', started_at=now(), revision=revision+1
                 WHERE tenant_id=? AND id=? AND revision=? AND status='DRAFT'
                """, tenant, id, revision) == 1;
    }

    public boolean cancel(UUID tenant, UUID id) {
        int changed = db.update("UPDATE campaign SET status='CANCELLED', finished_at=now(), revision=revision+1 WHERE tenant_id=? AND id=? AND status IN ('DRAFT','SENDING')",
                tenant, id);
        db.update("UPDATE campaign_recipient SET status='SKIPPED', error='Campaña cancelada' WHERE tenant_id=? AND campaign_id=? AND status='PENDING'", tenant, id);
        return changed == 1;
    }

    // --- Cola ---

    /** Toma un lote de pendientes de campañas en curso; otro trabajador simultáneo no ve las mismas filas. */
    public List<Recipient> claimBatch(int size) {
        return db.query("""
                SELECT r.tenant_id, r.campaign_id, r.contact_id, r.destination, k.full_name, k.unsubscribe_token
                  FROM campaign_recipient r
                  JOIN campaign c ON c.tenant_id = r.tenant_id AND c.id = r.campaign_id AND c.status = 'SENDING'
                  JOIN contact k ON k.tenant_id = r.tenant_id AND k.id = r.contact_id
                 WHERE r.status = 'PENDING'
                 ORDER BY c.started_at, r.contact_id
                 LIMIT ? FOR UPDATE OF r SKIP LOCKED
                """, (rs, n) -> new Recipient(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getString(4), rs.getString(5), rs.getString(6)), size);
    }

    /** Vuelve a comprobar el consentimiento justo antes de enviar (pudo darse de baja después de encolar). */
    public boolean stillConsents(Recipient r, Channel channel) {
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT unsubscribed_at IS NULL AND merged_into IS NULL AND CASE WHEN ? = 'EMAIL' THEN email_opt_in ELSE whatsapp_opt_in END
                  FROM contact WHERE tenant_id=? AND id=?
                """, Boolean.class, channel.name(), r.tenant(), r.contact()));
    }

    public void mark(Recipient r, String status, String error) {
        db.update("""
                UPDATE campaign_recipient SET status=?, error=left(?, 300), attempts=attempts+1,
                       sent_at=CASE WHEN ?='SENT' THEN now() END
                 WHERE tenant_id=? AND campaign_id=? AND contact_id=?
                """, status, error, status, r.tenant(), r.campaign(), r.contact());
    }

    public int finishDrained() {
        return db.update("""
                UPDATE campaign c SET status='SENT', finished_at=now(), revision=revision+1
                 WHERE status='SENDING' AND NOT EXISTS (SELECT 1 FROM campaign_recipient r
                       WHERE r.tenant_id=c.tenant_id AND r.campaign_id=c.id AND r.status='PENDING')
                """);
    }

    public String storeName(UUID tenant) {
        return db.queryForObject("SELECT name FROM tenant WHERE id=?", String.class, tenant);
    }

    // --- Consentimiento ---

    public Optional<Preferences> preferencesByToken(String token) {
        return db.query("""
                SELECT t.name, c.email, c.email_opt_in AND c.unsubscribed_at IS NULL, c.whatsapp_opt_in AND c.unsubscribed_at IS NULL
                  FROM contact c JOIN tenant t ON t.id = c.tenant_id WHERE c.unsubscribe_token = ?
                """, (rs, n) -> new Preferences(rs.getString(1), mask(rs.getString(2)), rs.getBoolean(3), rs.getBoolean(4)), token)
                .stream().findFirst();
    }

    public boolean unsubscribe(String token) {
        return db.update("""
                UPDATE contact SET email_opt_in=false, whatsapp_opt_in=false, unsubscribed_at=coalesce(unsubscribed_at, now()), updated_at=now()
                 WHERE unsubscribe_token=?
                """, token) == 1;
    }

    public void setConsent(UUID tenant, UUID contact, Boolean email, Boolean whatsapp) {
        db.update("""
                UPDATE contact SET email_opt_in=coalesce(?, email_opt_in), whatsapp_opt_in=coalesce(?, whatsapp_opt_in),
                       consent_at=CASE WHEN coalesce(?, false) OR coalesce(?, false) THEN now() ELSE consent_at END,
                       unsubscribed_at=CASE WHEN coalesce(?, false) OR coalesce(?, false) THEN NULL ELSE unsubscribed_at END,
                       updated_at=now()
                 WHERE tenant_id=? AND id=?
                """, email, whatsapp, email, whatsapp, email, whatsapp, tenant, contact);
    }

    /** El comprador marcó "quiero recibir novedades" en el checkout de esta reserva. */
    public boolean reservationOptIn(UUID tenant, UUID reservation) {
        return Boolean.TRUE.equals(db.query("SELECT marketing_opt_in FROM cart_reservation WHERE tenant_id=? AND id=?",
                (rs, n) -> rs.getBoolean(1), tenant, reservation).stream().findFirst().orElse(false));
    }

    public UUID orderReservation(UUID tenant, UUID order) {
        return db.query("SELECT reservation_id FROM web_checkout_order WHERE tenant_id=? AND id=?",
                (rs, n) -> rs.getObject(1, UUID.class), tenant, order).stream().findFirst().orElse(null);
    }

    public Optional<Preferences> preferences(UUID tenant, UUID contact) {
        return db.query("""
                SELECT t.name, c.email, c.email_opt_in AND c.unsubscribed_at IS NULL, c.whatsapp_opt_in AND c.unsubscribed_at IS NULL
                  FROM contact c JOIN tenant t ON t.id = c.tenant_id WHERE c.tenant_id = ? AND c.id = ?
                """, (rs, n) -> new Preferences(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getBoolean(4)), tenant, contact)
                .stream().findFirst();
    }

    /** Totales de contactos con consentimiento, para el panel. */
    public record Audience(long email, long whatsapp, long unsubscribed) {}

    public Audience audience(UUID tenant) {
        return db.queryForObject("""
                SELECT count(*) FILTER (WHERE email_opt_in AND email IS NOT NULL AND unsubscribed_at IS NULL),
                       count(*) FILTER (WHERE whatsapp_opt_in AND phone IS NOT NULL AND unsubscribed_at IS NULL),
                       count(*) FILTER (WHERE unsubscribed_at IS NOT NULL)
                  FROM contact WHERE tenant_id = ? AND merged_into IS NULL
                """, (rs, n) -> new Audience(rs.getLong(1), rs.getLong(2), rs.getLong(3)), tenant);
    }

    // --- Carrito abandonado ---

    public AbandonedSettings abandonedSettings(UUID tenant) {
        return db.query("SELECT enabled, delay_minutes, template_name, template_lang, revision FROM abandoned_cart_settings WHERE tenant_id=?",
                (rs, n) -> new AbandonedSettings(rs.getBoolean(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getLong(5)), tenant)
                .stream().findFirst().orElse(new AbandonedSettings(false, 60, null, null, 0));
    }

    public boolean saveAbandoned(UUID tenant, long revision, boolean enabled, int delay, String template, String lang) {
        if (revision == 0)
            return db.update("""
                    INSERT INTO abandoned_cart_settings(tenant_id, enabled, delay_minutes, template_name, template_lang)
                    VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING
                    """, tenant, enabled, delay, template, lang) == 1;
        return db.update("""
                UPDATE abandoned_cart_settings SET enabled=?, delay_minutes=?, template_name=?, template_lang=?, revision=revision+1
                 WHERE tenant_id=? AND revision=?
                """, enabled, delay, template, lang, tenant, revision) == 1;
    }

    /**
     * Reservas con datos de contacto que no terminaron en pedido, pasada la espera configurada y de menos de 24 horas.
     * La reserva dura 15 minutos: su creación es expires_at - 15 min.
     */
    public List<AbandonedCart> abandonedCarts(int limit) {
        return db.query("""
                SELECT r.tenant_id, r.id, normalize_phone(r.guest_phone), r.guest_name
                  FROM cart_reservation r JOIN abandoned_cart_settings s ON s.tenant_id = r.tenant_id AND s.enabled
                 WHERE r.guest_phone IS NOT NULL AND r.marketing_opt_in
                   AND r.expires_at - interval '15 minutes' < now() - make_interval(mins => s.delay_minutes)
                   AND r.expires_at > now() - interval '24 hours'
                   AND NOT EXISTS (SELECT 1 FROM web_checkout_order o WHERE o.tenant_id = r.tenant_id AND o.reservation_id = r.id)
                   AND NOT EXISTS (SELECT 1 FROM abandoned_cart_reminder a WHERE a.tenant_id = r.tenant_id AND a.reservation_id = r.id)
                   AND NOT EXISTS (SELECT 1 FROM contact c WHERE c.tenant_id = r.tenant_id AND c.phone = normalize_phone(r.guest_phone)
                                    AND c.unsubscribed_at IS NOT NULL)
                 ORDER BY r.expires_at LIMIT ?
                """, (rs, n) -> new AbandonedCart(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4)), limit);
    }

    /** Reserva el recordatorio antes de enviarlo: un reinicio a mitad de camino nunca manda dos. */
    public boolean claimReminder(AbandonedCart cart) {
        return db.update("INSERT INTO abandoned_cart_reminder(tenant_id, reservation_id, phone, status) VALUES (?,?,?,'SKIPPED') ON CONFLICT DO NOTHING",
                cart.tenant(), cart.reservation(), cart.phone() == null ? "0000000" : cart.phone()) == 1;
    }

    public void finishReminder(AbandonedCart cart, String status, String error) {
        db.update("UPDATE abandoned_cart_reminder SET status=?, error=left(?, 300) WHERE tenant_id=? AND reservation_id=?",
                status, error, cart.tenant(), cart.reservation());
    }

    public record ReminderStats(long sent, long failed) {}

    public ReminderStats reminderStats(UUID tenant) {
        return db.queryForObject("""
                SELECT count(*) FILTER (WHERE status='SENT'), count(*) FILTER (WHERE status='FAILED')
                  FROM abandoned_cart_reminder WHERE tenant_id=? AND created_at > now() - interval '30 days'
                """, (rs, n) -> new ReminderStats(rs.getLong(1), rs.getLong(2)), tenant);
    }

    private static String mask(String email) {
        if (email == null || !email.contains("@")) return null;
        int at = email.indexOf('@');
        return email.charAt(0) + "•••" + email.substring(at);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
