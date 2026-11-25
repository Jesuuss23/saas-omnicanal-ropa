package com.saas.omnicanal.payments.repository;

import com.saas.omnicanal.payments.model.OrderPaymentStatus;
import com.saas.omnicanal.payments.model.PayableOrder;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderPaymentRepository {
    private final JdbcTemplate db;

    public OrderPaymentRepository(JdbcTemplate db) {
        this.db = db;
    }

    private static final String PAYABLE = """
            SELECT o.tenant_id,o.id,o.reservation_id,o.status,
                   o.merchandise_total + CASE WHEN o.free_delivery THEN 0 ELSE coalesce(o.delivery_fee,0) END,
                   (o.delivery_method='PICKUP' OR o.free_delivery OR o.delivery_fee IS NOT NULL),
                   (NOT r.released AND r.expires_at>clock_timestamp()), r.expires_at, t.name
              FROM web_checkout_order o
              JOIN cart_reservation r ON (r.tenant_id,r.id)=(o.tenant_id,o.reservation_id)
              JOIN tenant t ON t.id=o.tenant_id
             WHERE o.tenant_id=? AND o.id=?
            """;

    public Optional<PayableOrder> payable(UUID tenant, UUID order, boolean lock) {
        return db.query(PAYABLE + (lock ? " FOR UPDATE OF o, r" : ""), (rs, n) -> new PayableOrder(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4),
                rs.getBigDecimal(5), rs.getBoolean(6), rs.getBoolean(7), rs.getObject(8, OffsetDateTime.class),
                rs.getString(9)), tenant, order).stream().findFirst();
    }

    public void upsertPayment(UUID tenant, String paymentId, UUID order, OrderPaymentStatus status, String providerStatus,
                              BigDecimal amount, String currency) {
        db.update("""
                INSERT INTO web_order_payment(tenant_id,provider,provider_payment_id,order_id,status,provider_status,amount,currency)
                VALUES (?,'CULQI',?,?,?,?,?,?)
                ON CONFLICT (tenant_id,provider,provider_payment_id) DO UPDATE SET status=excluded.status,
                    provider_status=excluded.provider_status, amount=excluded.amount, updated_at=now()
                """, tenant, paymentId, order, status.name(), providerStatus, amount, currency);
    }

    public void flagReview(UUID tenant, String paymentId, String reason) {
        db.update("""
                UPDATE web_order_payment SET requires_review=true, review_reason=left(?,200), updated_at=now()
                 WHERE tenant_id=? AND provider='CULQI' AND provider_payment_id=?
                """, reason, tenant, paymentId);
    }

    /**
     * Cierre único del pedido: pasa a PAID, la reserva queda consumida y el stock reservado se descuenta
     * del físico. Solo aplica una vez (el pedido debe seguir PENDING_PAYMENT).
     */
    public boolean markPaid(UUID tenant, UUID order, UUID reservation) {
        int changed = db.update("""
                UPDATE web_checkout_order SET status='PAID', paid_at=now()
                 WHERE tenant_id=? AND id=? AND status='PENDING_PAYMENT'
                """, tenant, order);
        if (changed == 0) return false;
        db.update("UPDATE cart_reservation SET released=true WHERE tenant_id=? AND id=?", tenant, reservation);
        db.update("""
                UPDATE product_variant v SET stock_physical=coalesce(v.stock_physical,0)-l.quantity,
                       stock_reserved=coalesce(v.stock_reserved,0)-l.quantity
                  FROM cart_reservation_line l
                 WHERE l.tenant_id=? AND l.reservation_id=? AND (v.tenant_id,v.id)=(l.tenant_id,l.variant_id)
                """, tenant, reservation);
        return true;
    }

    /** Pedido de la tienda al que pertenece un cargo ya registrado (para reembolsos sin metadata). */
    public Optional<UUID> orderOfCharge(UUID tenant, String chargeId) {
        return db.queryForList("""
                SELECT order_id FROM web_order_payment WHERE tenant_id=? AND provider='CULQI' AND provider_payment_id=?
                """, UUID.class, tenant, chargeId).stream().findFirst();
    }

    public record PaymentView(String status, BigDecimal amount, boolean requiresReview, OffsetDateTime updatedAt) {}

    public Optional<PaymentView> latest(UUID tenant, UUID order) {
        return db.query("""
                SELECT status,amount,requires_review,updated_at FROM web_order_payment
                 WHERE tenant_id=? AND order_id=? ORDER BY updated_at DESC LIMIT 1
                """, (rs, n) -> new PaymentView(rs.getString(1), rs.getBigDecimal(2), rs.getBoolean(3),
                rs.getObject(4, OffsetDateTime.class)), tenant, order).stream().findFirst();
    }
}
