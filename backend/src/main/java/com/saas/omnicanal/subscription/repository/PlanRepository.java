package com.saas.omnicanal.subscription.repository;

import com.saas.omnicanal.subscription.model.Entitlement;
import com.saas.omnicanal.subscription.model.Plan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PlanRepository {
    private final JdbcTemplate db;

    public PlanRepository(JdbcTemplate db) {
        this.db = db;
    }

    public record PlanRow(String code, String name, int priceCents, String currency, String culqiPlanId, boolean publicPlan) {}

    public List<PlanRow> all() {
        return db.query("SELECT code,name,monthly_price_cents,currency,culqi_plan_id,public FROM saas_plan ORDER BY sort_order,code",
                (rs, n) -> new PlanRow(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5),
                        rs.getBoolean(6)));
    }

    public Optional<PlanRow> find(String code) {
        return all().stream().filter(p -> p.code().equals(code)).findFirst();
    }

    public List<Plan> publicPlans() {
        Map<String, List<Entitlement>> byPlan = new LinkedHashMap<>();
        db.query("SELECT plan_code,entitlement_key,enabled,limit_value FROM saas_plan_entitlement ORDER BY entitlement_key",
                rs -> {
                    byPlan.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(new Entitlement(rs.getString(2),
                            rs.getBoolean(3), (Long) rs.getObject(4)));
                });
        return all().stream().filter(PlanRow::publicPlan).map(p -> new Plan(p.code(), p.name(), p.priceCents(),
                p.currency(), p.culqiPlanId() != null, byPlan.getOrDefault(p.code(), List.of()))).toList();
    }

    public boolean setCulqiPlanId(String code, String culqiPlanId) {
        return db.update("UPDATE saas_plan SET culqi_plan_id=? WHERE code=?", culqiPlanId, code) == 1;
    }

    /** Entitlements efectivos: los del plan, sobrescritos por overrides vigentes de la tienda. */
    public Map<String, Entitlement> effective(UUID tenant, String planCode) {
        Map<String, Entitlement> result = new LinkedHashMap<>();
        if (planCode != null) {
            db.query("SELECT entitlement_key,enabled,limit_value FROM saas_plan_entitlement WHERE plan_code=?",
                    rs -> { result.put(rs.getString(1), new Entitlement(rs.getString(1), rs.getBoolean(2), (Long) rs.getObject(3))); },
                    planCode);
        }
        db.query("""
                SELECT entitlement_key,enabled,limit_value FROM tenant_entitlement_override
                 WHERE tenant_id=? AND (expires_at IS NULL OR expires_at>now())
                """, rs -> { result.put(rs.getString(1), new Entitlement(rs.getString(1), rs.getBoolean(2), (Long) rs.getObject(3))); },
                tenant);
        return result;
    }

    public record OverrideRow(String key, boolean enabled, Long limit, java.time.OffsetDateTime expiresAt, String reason) {}

    public List<OverrideRow> overrides(UUID tenant) {
        return db.query("""
                SELECT entitlement_key,enabled,limit_value,expires_at,reason FROM tenant_entitlement_override
                 WHERE tenant_id=? ORDER BY entitlement_key
                """, (rs, n) -> new OverrideRow(rs.getString(1), rs.getBoolean(2), (Long) rs.getObject(3),
                rs.getObject(4, java.time.OffsetDateTime.class), rs.getString(5)), tenant);
    }

    public void upsertOverride(UUID tenant, String key, boolean enabled, Long limit, java.time.OffsetDateTime expiresAt, String reason) {
        db.update("""
                INSERT INTO tenant_entitlement_override(tenant_id,entitlement_key,enabled,limit_value,expires_at,reason)
                VALUES (?,?,?,?,?,?)
                ON CONFLICT (tenant_id,entitlement_key) DO UPDATE SET enabled=excluded.enabled,
                    limit_value=excluded.limit_value, expires_at=excluded.expires_at, reason=excluded.reason, created_at=now()
                """, tenant, key, enabled, limit, expiresAt, reason);
    }

    public void deleteOverride(UUID tenant, String key) {
        db.update("DELETE FROM tenant_entitlement_override WHERE tenant_id=? AND entitlement_key=?", tenant, key);
    }
}
