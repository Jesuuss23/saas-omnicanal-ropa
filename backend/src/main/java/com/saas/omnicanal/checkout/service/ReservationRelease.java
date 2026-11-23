package com.saas.omnicanal.checkout.service;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationRelease {
    private final JdbcTemplate db;

    ReservationRelease(JdbcTemplate db) { this.db = db; }

    @Transactional
    public void release(UUID store, UUID key) {
        int changed = db.update("""
                UPDATE cart_reservation SET released=true, guest_name=NULL, guest_phone=NULL
                 WHERE tenant_id=? AND id=? AND NOT released
                """, store, key);
        if (changed == 0) return;
        db.update("""
                UPDATE web_checkout_order o SET status=CASE WHEN r.expires_at<=clock_timestamp()
                        THEN 'EXPIRED' ELSE 'CANCELLED' END
                FROM cart_reservation r WHERE r.tenant_id=? AND r.id=?
                  AND (o.tenant_id,o.reservation_id)=(r.tenant_id,r.id) AND o.status='PENDING_PAYMENT'
                """, store, key);
        db.update("""
                UPDATE product_variant v SET stock_reserved=v.stock_reserved-l.quantity
                  FROM cart_reservation_line l
                 WHERE l.tenant_id=? AND l.reservation_id=?
                   AND (v.tenant_id,v.id)=(l.tenant_id,l.variant_id)
                """, store, key);
    }
}
