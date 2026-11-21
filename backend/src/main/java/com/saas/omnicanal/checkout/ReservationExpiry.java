package com.saas.omnicanal.checkout;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@EnableScheduling
class ReservationExpiry {
    private final JdbcTemplate db;

    private final ReservationRelease release;

    ReservationExpiry(JdbcTemplate db, ReservationRelease release) {
        this.db=db;
        this.release=release;
    }

    record Key(UUID store, UUID reservation) {}

    @Scheduled(fixedDelay=5000)
    @Transactional
    public void releaseExpired() {
        var keys=db.query("""
                SELECT tenant_id,id FROM cart_reservation WHERE NOT released AND expires_at<=now()
                ORDER BY expires_at LIMIT 50 FOR UPDATE SKIP LOCKED
                """,(rs,n)->new Key(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)));
        for (var key : keys) {
            release.release(key.store(), key.reservation());
        }
    }
}
