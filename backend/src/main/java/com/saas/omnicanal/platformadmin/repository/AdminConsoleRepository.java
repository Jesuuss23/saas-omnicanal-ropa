package com.saas.omnicanal.platformadmin.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Consultas de la consola del superadmin: métricas de la plataforma y fichas de tiendas. */
@Repository
public class AdminConsoleRepository {
    private final JdbcTemplate db;

    public AdminConsoleRepository(JdbcTemplate db) {
        this.db = db;
    }

    public record Kpis(long stores, long activePaid, long trials, long trialsEndingSoon, long pastDue, long suspended,
                       long newStores30d, long pendingSignups, long mrrCents, long products, long variants,
                       long webOrders30d, long posSales30d) {}

    public record WeekCount(LocalDate week, long stores) {}

    public record TenantRow(UUID id, String name, String ownerEmail, boolean ownerActive, String planCode, String planName,
                            String status, Instant trialEndsAt, Instant createdAt, boolean suspended, long products) {}

    public record TenantDetail(UUID id, String name, String domain, Instant createdAt, Instant suspendedAt, String suspendedReason,
                               String ownerEmail, String ownerName, String ownerPhone, boolean ownerActive, boolean ownerMfa,
                               String planCode, String planName, String status, Instant trialEndsAt, Instant periodEnd,
                               Instant graceEndsAt, String culqiSubscriptionId, String whatsappProvider, boolean mercadoPagoLinked,
                               long products, long variants, long webOrders30d, BigDecimal webRevenue30d, long posSales30d,
                               BigDecimal posRevenue30d, long contacts) {}

    public record SubscriptionEvent(String fromStatus, String toStatus, String planCode, String source, String detail, Instant createdAt) {}

    public record PaymentEvent(String provider, String eventId, UUID tenant, String tenantName, String type, String status,
                               String error, Instant receivedAt) {}

    public Kpis kpis() {
        return db.queryForObject("""
                SELECT
                  (SELECT count(*) FROM tenant),
                  (SELECT count(*) FROM tenant_subscription s JOIN saas_plan p ON p.code = s.plan_code
                     WHERE s.status = 'ACTIVE' AND p.monthly_price_cents > 0),
                  (SELECT count(*) FROM tenant_subscription WHERE status = 'TRIAL'),
                  (SELECT count(*) FROM tenant_subscription WHERE status = 'TRIAL' AND trial_ends_at < now() + interval '7 days'),
                  (SELECT count(*) FROM tenant_subscription WHERE status IN ('GRACE','PENDING_PAYMENT','TRIAL_EXPIRED')),
                  (SELECT count(*) FROM tenant WHERE suspended_at IS NOT NULL),
                  (SELECT count(*) FROM tenant WHERE created_at > now() - interval '30 days'),
                  (SELECT count(*) FROM signup_request WHERE confirmed_at IS NULL AND expires_at > now()),
                  (SELECT coalesce(sum(p.monthly_price_cents), 0) FROM tenant_subscription s JOIN saas_plan p ON p.code = s.plan_code
                     WHERE s.status IN ('ACTIVE','GRACE')),
                  (SELECT count(*) FROM product),
                  (SELECT count(*) FROM product_variant),
                  (SELECT count(*) FROM web_checkout_order WHERE created_at > now() - interval '30 days'),
                  (SELECT count(*) FROM pos_sale WHERE created_at > now() - interval '30 days')
                """, (rs, row) -> new Kpis(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getLong(10), rs.getLong(11), rs.getLong(12),
                rs.getLong(13)));
    }

    public List<WeekCount> newStoresByWeek(int weeks) {
        return db.query("""
                SELECT w::date, count(t.id)
                FROM generate_series(date_trunc('week', now()) - make_interval(weeks => ? - 1), date_trunc('week', now()), interval '1 week') w
                LEFT JOIN tenant t ON date_trunc('week', t.created_at) = w
                GROUP BY w ORDER BY w
                """, (rs, row) -> new WeekCount(rs.getObject(1, LocalDate.class), rs.getLong(2)), weeks);
    }

    public Map<String, Long> statusCounts() {
        var counts = new LinkedHashMap<String, Long>();
        db.query("SELECT status, count(*) FROM tenant_subscription GROUP BY status ORDER BY count(*) DESC",
                rs -> { counts.put(rs.getString(1), rs.getLong(2)); });
        return counts;
    }

    public List<TenantRow> tenants(String query, String status, int page, int size) {
        String like = query == null || query.isBlank() ? null : "%" + query.strip().toLowerCase().replace("%", "\\%").replace("_", "\\_") + "%";
        String filter = status == null || status.isBlank() ? null : status;
        return db.query("""
                SELECT t.id, t.name, o.email, o.password_hash IS NOT NULL, s.plan_code, p.name, s.status, s.trial_ends_at,
                       t.created_at, t.suspended_at IS NOT NULL,
                       (SELECT count(*) FROM product pr WHERE pr.tenant_id = t.id)
                FROM tenant t
                LEFT JOIN tenant_owner o ON o.tenant_id = t.id
                LEFT JOIN tenant_subscription s ON s.tenant_id = t.id
                LEFT JOIN saas_plan p ON p.code = s.plan_code
                WHERE (?::text IS NULL OR lower(t.name) LIKE ?::text OR lower(o.email) LIKE ?::text OR t.id::text = ?::text)
                  AND (?::text IS NULL OR (?::text = 'SUSPENDED_STORE' AND t.suspended_at IS NOT NULL) OR s.status = ?::text)
                ORDER BY t.created_at DESC NULLS LAST, t.name LIMIT ? OFFSET ?
                """, this::tenantRow, like, like, like, query == null ? null : query.strip(), filter, filter, filter, size + 1, page * size);
    }

    private TenantRow tenantRow(ResultSet rs, int row) throws SQLException {
        return new TenantRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getString(5),
                rs.getString(6), rs.getString(7), instant(rs.getTimestamp(8)), instant(rs.getTimestamp(9)), rs.getBoolean(10), rs.getLong(11));
    }

    public Optional<TenantDetail> tenant(UUID id) {
        return db.query("""
                SELECT t.id, t.name, t.domain, t.created_at, t.suspended_at, t.suspended_reason,
                       o.email, o.full_name, o.phone, o.password_hash IS NOT NULL,
                       EXISTS (SELECT 1 FROM mfa_enrollment m WHERE m.subject_type = 'OWNER' AND m.subject_id = t.id::text AND m.secret_enc IS NOT NULL),
                       s.plan_code, p.name, s.status, s.trial_ends_at, s.current_period_end, s.grace_ends_at, s.culqi_subscription_id,
                       coalesce(w.active_provider, 'EVOLUTION'),
                       EXISTS (SELECT 1 FROM store_payment_account a WHERE a.tenant_id = t.id AND a.status = 'ACTIVE'),
                       (SELECT count(*) FROM product x WHERE x.tenant_id = t.id),
                       (SELECT count(*) FROM product_variant x WHERE x.tenant_id = t.id),
                       (SELECT count(*) FROM web_checkout_order x WHERE x.tenant_id = t.id AND x.created_at > now() - interval '30 days'),
                       (SELECT coalesce(sum(x.merchandise_total), 0) FROM web_checkout_order x WHERE x.tenant_id = t.id
                          AND x.status = 'PAID' AND x.created_at > now() - interval '30 days'),
                       (SELECT count(*) FROM pos_sale x WHERE x.tenant_id = t.id AND x.created_at > now() - interval '30 days'),
                       (SELECT coalesce(sum(x.total), 0) FROM pos_sale x WHERE x.tenant_id = t.id AND x.created_at > now() - interval '30 days'),
                       (SELECT count(*) FROM crm_contact_stage x WHERE x.tenant_id = t.id)
                FROM tenant t
                LEFT JOIN tenant_owner o ON o.tenant_id = t.id
                LEFT JOIN tenant_subscription s ON s.tenant_id = t.id
                LEFT JOIN saas_plan p ON p.code = s.plan_code
                LEFT JOIN whatsapp_channel_setting w ON w.tenant_id = t.id
                WHERE t.id = ?
                """, (rs, row) -> new TenantDetail(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), instant(rs.getTimestamp(4)),
                instant(rs.getTimestamp(5)), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getBoolean(10),
                rs.getBoolean(11), rs.getString(12), rs.getString(13), rs.getString(14), instant(rs.getTimestamp(15)),
                instant(rs.getTimestamp(16)), instant(rs.getTimestamp(17)), rs.getString(18), rs.getString(19), rs.getBoolean(20),
                rs.getLong(21), rs.getLong(22), rs.getLong(23), rs.getBigDecimal(24), rs.getLong(25), rs.getBigDecimal(26), rs.getLong(27)), id)
                .stream().findFirst();
    }

    public List<SubscriptionEvent> subscriptionEvents(UUID tenant, int limit) {
        return db.query("""
                SELECT from_status, to_status, plan_code, source, detail, created_at FROM tenant_subscription_event
                WHERE tenant_id = ? ORDER BY created_at DESC LIMIT ?
                """, (rs, row) -> new SubscriptionEvent(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), instant(rs.getTimestamp(6))), tenant, limit);
    }

    public List<PaymentEvent> paymentEvents(String provider, String status, int page, int size) {
        return db.query("""
                SELECT e.provider, e.event_id, e.tenant_id, t.name, e.event_type, e.status, e.error, e.received_at
                FROM payment_webhook_event e LEFT JOIN tenant t ON t.id = e.tenant_id
                WHERE (?::text IS NULL OR e.provider = ?::text) AND (?::text IS NULL OR e.status = ?::text)
                ORDER BY e.received_at DESC LIMIT ? OFFSET ?
                """, (rs, row) -> new PaymentEvent(rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), instant(rs.getTimestamp(8))),
                blank(provider), blank(provider), blank(status), blank(status), size + 1, page * size);
    }

    public boolean suspend(UUID tenant, String reason) {
        return db.update("UPDATE tenant SET suspended_at = now(), suspended_reason = ? WHERE id = ? AND suspended_at IS NULL",
                reason, tenant) == 1;
    }

    public boolean reactivate(UUID tenant) {
        return db.update("UPDATE tenant SET suspended_at = NULL, suspended_reason = NULL WHERE id = ? AND suspended_at IS NOT NULL",
                tenant) == 1;
    }

    public boolean isSuspended(UUID tenant) {
        return Boolean.TRUE.equals(db.query("SELECT suspended_at IS NOT NULL FROM tenant WHERE id = ?",
                rs -> rs.next() ? rs.getBoolean(1) : Boolean.FALSE, tenant));
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
