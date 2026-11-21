package com.saas.omnicanal.checkout;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReservationRelease {
    private final JdbcTemplate db;

    ReservationRelease(JdbcTemplate db) { this.db = db; }

    @Transactional
    public void release(UUID store, UUID key) {
        int changed = db.update("""
                UPDATE cart_reservation SET released=true
                 WHERE tenant_id=? AND id=? AND NOT released
                """, store, key);
        if (changed == 0) return;
        db.update("""
                UPDATE product_variant v SET stock_reserved=v.stock_reserved-l.quantity
                  FROM cart_reservation_line l
                 WHERE l.tenant_id=? AND l.reservation_id=?
                   AND (v.tenant_id,v.id)=(l.tenant_id,l.variant_id)
                """, store, key);
    }
}
