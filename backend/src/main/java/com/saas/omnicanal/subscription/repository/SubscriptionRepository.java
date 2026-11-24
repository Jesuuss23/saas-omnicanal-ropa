package com.saas.omnicanal.subscription.repository;

import com.saas.omnicanal.subscription.model.Subscription;
import com.saas.omnicanal.subscription.model.SubscriptionStatus;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SubscriptionRepository {
    private static final String COLUMNS = """
            tenant_id,plan_code,status,trial_ends_at,current_period_end,grace_ends_at,pending_plan_code,
            culqi_customer_id,culqi_card_id,culqi_subscription_id,revision""";
    private static final RowMapper<Subscription> ROW = (rs, n) -> new Subscription(rs.getObject(1, UUID.class),
            rs.getString(2), SubscriptionStatus.valueOf(rs.getString(3)), rs.getObject(4, OffsetDateTime.class),
            rs.getObject(5, OffsetDateTime.class), rs.getObject(6, OffsetDateTime.class), rs.getString(7),
            rs.getString(8), rs.getString(9), rs.getString(10), rs.getLong(11));

    private final JdbcTemplate db;

    public SubscriptionRepository(JdbcTemplate db) {
        this.db = db;
    }

    public Optional<Subscription> find(UUID tenant) {
        return db.query("SELECT " + COLUMNS + " FROM tenant_subscription WHERE tenant_id=?", ROW, tenant).stream().findFirst();
    }

    public Optional<Subscription> findForUpdate(UUID tenant) {
        return db.query("SELECT " + COLUMNS + " FROM tenant_subscription WHERE tenant_id=? FOR UPDATE", ROW, tenant)
                .stream().findFirst();
    }

    public Optional<Subscription> findByCulqiSubscription(String culqiSubscriptionId) {
        return db.query("SELECT " + COLUMNS + " FROM tenant_subscription WHERE culqi_subscription_id=? FOR UPDATE", ROW,
                culqiSubscriptionId).stream().findFirst();
    }

    public void startTrial(UUID tenant, int days) {
        db.update("""
                INSERT INTO tenant_subscription(tenant_id,plan_code,status,trial_ends_at)
                VALUES (?,'TRIAL','TRIAL',now() + make_interval(days => ?))
                ON CONFLICT (tenant_id) DO NOTHING
                """, tenant, days);
    }

    public void saveCulqiCustomer(UUID tenant, String customerId, String cardId, String email) {
        db.update("""
                UPDATE tenant_subscription SET culqi_customer_id=?, culqi_card_id=?, culqi_email=lower(?), updated_at=now()
                 WHERE tenant_id=?""", customerId, cardId, email, tenant);
    }

    /**
     * Suscripciones con un intento de Culqi vivo (pendiente o pagado) hechas con este correo. Sirve para los eventos de
     * cargo, que no traen el id de la suscripción. Bloquea las filas como {@link #findByCulqiSubscription}.
     */
    public java.util.List<Subscription> findByCulqiEmail(String email) {
        return db.query("SELECT " + COLUMNS + " FROM tenant_subscription"
                + " WHERE lower(culqi_email)=lower(?) AND culqi_subscription_id IS NOT NULL"
                + " AND status IN ('PENDING_PAYMENT','ACTIVE','GRACE') ORDER BY updated_at DESC FOR UPDATE", ROW, email);
    }

    public void markPending(UUID tenant, String planCode, String culqiSubscriptionId) {
        db.update("""
                UPDATE tenant_subscription SET status='PENDING_PAYMENT', pending_plan_code=?, culqi_subscription_id=?,
                       revision=revision+1, updated_at=now() WHERE tenant_id=?
                """, planCode, culqiSubscriptionId, tenant);
    }

    public void activate(UUID tenant, String planCode, OffsetDateTime periodEnd) {
        db.update("""
                UPDATE tenant_subscription SET status='ACTIVE', plan_code=?, pending_plan_code=NULL,
                       current_period_end=?, grace_ends_at=NULL, revision=revision+1, updated_at=now()
                 WHERE tenant_id=?
                """, planCode, periodEnd, tenant);
    }

    /** Extiende la prueba desde su fin actual (o desde hoy si ya venció) y la deja en TRIAL. */
    public void extendTrial(UUID tenant, int days) {
        db.update("""
                UPDATE tenant_subscription SET status='TRIAL',
                       trial_ends_at=greatest(coalesce(trial_ends_at, now()), now()) + make_interval(days => ?),
                       revision=revision+1, updated_at=now() WHERE tenant_id=?
                """, days, tenant);
    }

    public void setStatus(UUID tenant, SubscriptionStatus status, OffsetDateTime graceEndsAt) {
        db.update("""
                UPDATE tenant_subscription SET status=?, grace_ends_at=?, revision=revision+1, updated_at=now()
                 WHERE tenant_id=?
                """, status.name(), graceEndsAt, tenant);
    }

    public void logEvent(UUID tenant, SubscriptionStatus from, SubscriptionStatus to, String plan, String source, String detail) {
        db.update("""
                INSERT INTO tenant_subscription_event(tenant_id,from_status,to_status,plan_code,source,detail)
                VALUES (?,?,?,?,?,left(?,300))
                """, tenant, from == null ? null : from.name(), to.name(), plan, source, detail);
    }

    /** Transiciones por tiempo; devuelve cuántas filas cambió cada regla. */
    public Map<String, Integer> applyTimeTransitions(int graceDays) {
        Map<String, Integer> changed = new TreeMap<>();
        changed.put("trialExpired", db.update("""
                WITH moved AS (UPDATE tenant_subscription SET status='TRIAL_EXPIRED', revision=revision+1, updated_at=now()
                    WHERE status='TRIAL' AND trial_ends_at<=now() RETURNING tenant_id,plan_code)
                INSERT INTO tenant_subscription_event(tenant_id,from_status,to_status,plan_code,source)
                SELECT tenant_id,'TRIAL','TRIAL_EXPIRED',plan_code,'SCHEDULER' FROM moved
                """));
        changed.put("grace", db.update("""
                WITH moved AS (UPDATE tenant_subscription SET status='GRACE',
                        grace_ends_at=now() + make_interval(days => ?), revision=revision+1, updated_at=now()
                    WHERE status='ACTIVE' AND current_period_end IS NOT NULL AND current_period_end<=now()
                    RETURNING tenant_id,plan_code)
                INSERT INTO tenant_subscription_event(tenant_id,from_status,to_status,plan_code,source)
                SELECT tenant_id,'ACTIVE','GRACE',plan_code,'SCHEDULER' FROM moved
                """, graceDays));
        changed.put("suspended", db.update("""
                WITH moved AS (UPDATE tenant_subscription SET status='SUSPENDED', revision=revision+1, updated_at=now()
                    WHERE status='GRACE' AND grace_ends_at<=now() RETURNING tenant_id,plan_code)
                INSERT INTO tenant_subscription_event(tenant_id,from_status,to_status,plan_code,source)
                SELECT tenant_id,'GRACE','SUSPENDED',plan_code,'SCHEDULER' FROM moved
                """));
        return changed;
    }

    public Map<String, Long> countByStatus() {
        Map<String, Long> counts = new TreeMap<>();
        db.query("SELECT status,count(*) FROM tenant_subscription GROUP BY status",
                rs -> { counts.put(rs.getString(1), rs.getLong(2)); });
        counts.put("TRIAL_ENDING_3_DAYS", db.queryForObject("""
                SELECT count(*) FROM tenant_subscription WHERE status='TRIAL' AND trial_ends_at<=now()+interval '3 days'
                """, Long.class));
        return counts;
    }

    /**
     * Olvida el intento de suscripción en Culqi (pendiente o reemplazado) para poder empezar uno nuevo. Si estaba en
     * PENDING_PAYMENT vuelve a lo que corresponda por fechas: plan pagado vigente, prueba vigente o prueba vencida.
     */
    public void clearCulqiAttempt(UUID tenant) {
        db.update("""
                UPDATE tenant_subscription SET culqi_subscription_id=NULL, pending_plan_code=NULL,
                       status=CASE WHEN status<>'PENDING_PAYMENT' THEN status
                                   WHEN current_period_end>now() THEN 'ACTIVE'
                                   WHEN trial_ends_at>now() THEN 'TRIAL'
                                   ELSE 'TRIAL_EXPIRED' END,
                       revision=revision+1, updated_at=now() WHERE tenant_id=?
                """, tenant);
    }

    /** true si la suscripción lleva más de {@code minutes} minutos sin cambios (p. ej. un pago que nunca se confirmó). */
    public boolean unchangedFor(UUID tenant, int minutes) {
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT updated_at < now() - make_interval(mins => ?) FROM tenant_subscription WHERE tenant_id=?
                """, Boolean.class, minutes, tenant));
    }
}
