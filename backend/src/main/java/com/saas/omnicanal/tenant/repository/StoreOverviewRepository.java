package com.saas.omnicanal.tenant.repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Métricas y pasos de configuración para la página de inicio del panel de la tienda. */
@Repository
public class StoreOverviewRepository {
    private final JdbcTemplate db;

    public StoreOverviewRepository(JdbcTemplate db) {
        this.db = db;
    }

    public record Metrics(BigDecimal webRevenue30d, long webOrders30d, long pendingOrders, BigDecimal posRevenue30d, long posSales30d,
                          long products, long variants, long lowStock, long outOfStock) {}
    public record DaySales(LocalDate day, BigDecimal web, BigDecimal pos) {}
    public record RecentOrder(UUID id, String customer, BigDecimal total, String status, String deliveryMethod, Instant createdAt) {}
    public record Setup(boolean products, boolean photos, boolean appearance, boolean fulfillment, boolean whatsapp,
                        boolean payments, boolean fiscal, boolean twoFactor) {}

    public Metrics metrics(UUID tenant) {
        return db.queryForObject("""
                SELECT
                  (SELECT coalesce(sum(merchandise_total + coalesce(delivery_fee, 0)), 0) FROM web_checkout_order
                     WHERE tenant_id = ? AND status = 'PAID' AND created_at > now() - interval '30 days'),
                  (SELECT count(*) FROM web_checkout_order WHERE tenant_id = ? AND created_at > now() - interval '30 days'),
                  (SELECT count(*) FROM web_checkout_order WHERE tenant_id = ? AND status = 'PENDING_PAYMENT'),
                  (SELECT coalesce(sum(total), 0) FROM pos_sale WHERE tenant_id = ? AND created_at > now() - interval '30 days'),
                  (SELECT count(*) FROM pos_sale WHERE tenant_id = ? AND created_at > now() - interval '30 days'),
                  (SELECT count(*) FROM product WHERE tenant_id = ?),
                  (SELECT count(*) FROM product_variant WHERE tenant_id = ?),
                  (SELECT count(*) FROM product_variant WHERE tenant_id = ?
                     AND coalesce(stock_physical, 0) - coalesce(stock_reserved, 0) BETWEEN 1 AND 2),
                  (SELECT count(*) FROM product_variant WHERE tenant_id = ?
                     AND coalesce(stock_physical, 0) - coalesce(stock_reserved, 0) <= 0)
                """, (rs, n) -> new Metrics(rs.getBigDecimal(1), rs.getLong(2), rs.getLong(3), rs.getBigDecimal(4), rs.getLong(5),
                rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9)),
                tenant, tenant, tenant, tenant, tenant, tenant, tenant, tenant, tenant);
    }

    /** Ventas por día (hora de Lima) de los últimos {@code days} días, web pagado y POS por separado. */
    public List<DaySales> salesByDay(UUID tenant, int days) {
        return db.query("""
                WITH d AS (SELECT generate_series((now() AT TIME ZONE 'America/Lima')::date - (? - 1),
                                                  (now() AT TIME ZONE 'America/Lima')::date, interval '1 day')::date AS day)
                SELECT d.day,
                  (SELECT coalesce(sum(o.merchandise_total + coalesce(o.delivery_fee, 0)), 0) FROM web_checkout_order o
                     WHERE o.tenant_id = ? AND o.status = 'PAID' AND (o.created_at AT TIME ZONE 'America/Lima')::date = d.day),
                  (SELECT coalesce(sum(s.total), 0) FROM pos_sale s
                     WHERE s.tenant_id = ? AND (s.created_at AT TIME ZONE 'America/Lima')::date = d.day)
                FROM d ORDER BY d.day
                """, (rs, n) -> new DaySales(rs.getObject(1, LocalDate.class), rs.getBigDecimal(2), rs.getBigDecimal(3)),
                days, tenant, tenant);
    }

    public List<RecentOrder> recentOrders(UUID tenant, int limit) {
        return db.query("""
                SELECT id, guest_name, merchandise_total + coalesce(delivery_fee, 0), status, delivery_method, created_at
                FROM web_checkout_order WHERE tenant_id = ? ORDER BY created_at DESC LIMIT ?
                """, (rs, n) -> new RecentOrder(rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3), rs.getString(4),
                rs.getString(5), instant(rs.getTimestamp(6))), tenant, limit);
    }

    public Setup setup(UUID tenant) {
        return db.queryForObject("""
                SELECT
                  EXISTS (SELECT 1 FROM product WHERE tenant_id = ?),
                  EXISTS (SELECT 1 FROM product_image WHERE tenant_id = ?),
                  EXISTS (SELECT 1 FROM store_appearance WHERE tenant_id = ? AND (logo IS NOT NULL OR logo_storage_url IS NOT NULL OR tagline IS NOT NULL)),
                  EXISTS (SELECT 1 FROM tenant WHERE id = ? AND (pickup_enabled OR delivery_per_km IS NOT NULL)),
                  EXISTS (SELECT 1 FROM whatsapp_inbound_message WHERE tenant_id = ?)
                    OR EXISTS (SELECT 1 FROM whatsapp_cloud_credential WHERE tenant_id = ? AND status = 'VERIFIED'),
                  EXISTS (SELECT 1 FROM store_payment_account WHERE tenant_id = ? AND status = 'ACTIVE'),
                  EXISTS (SELECT 1 FROM tenant_fiscal_issuer WHERE tenant_id = ?),
                  EXISTS (SELECT 1 FROM mfa_enrollment WHERE subject_type = 'OWNER' AND subject_id = ?::text AND secret_enc IS NOT NULL)
                """, (rs, n) -> new Setup(rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4), rs.getBoolean(5),
                rs.getBoolean(6), rs.getBoolean(7), rs.getBoolean(8)),
                tenant, tenant, tenant, tenant, tenant, tenant, tenant, tenant, tenant);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
