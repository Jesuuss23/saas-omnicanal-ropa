package com.saas.omnicanal.checkout;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/public/stores/{store}/reservation/guest")
class GuestCheckoutApi {
    private final JdbcTemplate db;

    GuestCheckoutApi(JdbcTemplate db) { this.db = db; }

    record Contact(@NotBlank @Size(max=150) String name,
                   @NotBlank @Pattern(regexp="[+]?[0-9]{7,15}") String phone) {}

    record Order(UUID id, BigDecimal total) {}
    record Checkout(String name, String phone, Order order) {}

    private void lockActive(UUID store, UUID key) {
        var active = db.query("""
                SELECT NOT released AND expires_at>clock_timestamp() FROM cart_reservation
                WHERE tenant_id=? AND id=? FOR UPDATE
                """, (rs,n)->rs.getBoolean(1), store,key);
        if (active.isEmpty() || !active.get(0)) throw new ResponseStatusException(HttpStatus.GONE);
    }

    @PostMapping
    @Transactional
    ResponseEntity<Order> confirm(@PathVariable UUID store, @RequestHeader("X-Reservation-Key") UUID key) {
        lockActive(store,key);
        db.update("""
                INSERT INTO web_checkout_order(tenant_id,reservation_id,guest_name,guest_phone,merchandise_total)
                SELECT r.tenant_id,r.id,r.guest_name,r.guest_phone,sum(l.quantity*l.unit_price)
                FROM cart_reservation r JOIN cart_reservation_line l
                  ON (l.tenant_id,l.reservation_id)=(r.tenant_id,r.id)
                WHERE r.tenant_id=? AND r.id=? AND r.guest_name IS NOT NULL
                GROUP BY r.tenant_id,r.id ON CONFLICT (tenant_id,reservation_id) DO NOTHING
                """,store,key);
        Order order=db.query("""
                SELECT id,merchandise_total FROM web_checkout_order WHERE tenant_id=? AND reservation_id=?
                """,(rs,n)->new Order(rs.getObject(1,UUID.class),rs.getBigDecimal(2)),store,key)
                .stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(order);
    }

    @GetMapping
    ResponseEntity<Checkout> read(@PathVariable UUID store, @RequestHeader("X-Reservation-Key") UUID key) {
        var contact = db.query("""
                SELECT r.guest_name,r.guest_phone,o.id,o.merchandise_total FROM cart_reservation r
                LEFT JOIN web_checkout_order o ON (o.tenant_id,o.reservation_id)=(r.tenant_id,r.id)
                WHERE r.tenant_id=? AND r.id=? AND NOT r.released AND r.expires_at>clock_timestamp()
                """, (rs, n) -> new Checkout(rs.getString(1), rs.getString(2),rs.getObject(3)==null ? null :
                    new Order(rs.getObject(3,UUID.class),rs.getBigDecimal(4))), store, key)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(contact);
    }

    @Transactional
    @PutMapping
    ResponseEntity<Contact> save(@PathVariable UUID store, @RequestHeader("X-Reservation-Key") UUID key,
                                 @Valid @RequestBody Contact input) {
        lockActive(store,key);
        var contact = new Contact(input.name().strip(), input.phone());
        int changed = db.update("""
                UPDATE cart_reservation SET guest_name=?, guest_phone=?
                WHERE tenant_id=? AND id=? AND NOT EXISTS (SELECT 1 FROM web_checkout_order o
                    WHERE (o.tenant_id,o.reservation_id)=(cart_reservation.tenant_id,cart_reservation.id))
                """, contact.name(), contact.phone(), store, key);
        if (changed==0) throw new ResponseStatusException(HttpStatus.CONFLICT);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(contact);
    }
}
