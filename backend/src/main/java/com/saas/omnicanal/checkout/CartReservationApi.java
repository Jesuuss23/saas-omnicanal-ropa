package com.saas.omnicanal.checkout;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/public/stores/{store}/reservation")
class CartReservationApi {
    private final JdbcTemplate db;

    private final ReservationRelease release;

    CartReservationApi(JdbcTemplate db, ReservationRelease release) {
        this.db = db;
        this.release = release;
    }

    record Cancellation(@NotNull UUID key) {}
    record CancellationReceipt(boolean released) {}

    @DeleteMapping
    CancellationReceipt cancel(@PathVariable UUID store, @Valid @RequestBody Cancellation body) {
        release.release(store, body.key());
        return new CancellationReceipt(true);
    }

    record Item(@NotNull UUID id, @Min(1) int quantity,
                @NotNull @Positive @Digits(integer=8, fraction=2) BigDecimal price) {}
    record Request(@NotNull UUID key, @NotEmpty @Size(max=100) List<@NotNull @Valid Item> items) {}
    record Receipt(Instant expiresAt) {}

    @PostMapping
    @Transactional
    Receipt reserve(@PathVariable UUID store, @Valid @RequestBody Request body) {
        var items = new TreeMap<UUID,Item>();
        for (var item : body.items()) {
            if (items.put(item.id(), item) != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        int created = db.update("""
                INSERT INTO cart_reservation(tenant_id,id)
                SELECT id, ? FROM tenant WHERE id=? ON CONFLICT DO NOTHING
                """,body.key(),store);
        var receipts = db.query("""
                SELECT expires_at, released OR expires_at <= clock_timestamp() FROM cart_reservation
                WHERE tenant_id=? AND id=? FOR UPDATE
                """,(rs,n)->{
                    if (rs.getBoolean(2)) throw new ResponseStatusException(HttpStatus.GONE);
                    return new Receipt(rs.getTimestamp(1).toInstant());
                },store,body.key());
        if (receipts.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (created == 0) {
            var saved = db.query("""
                    SELECT variant_id,quantity,unit_price FROM cart_reservation_line WHERE tenant_id=? AND reservation_id=?
                    """,(rs,n)->new Item(rs.getObject(1,UUID.class),rs.getInt(2),rs.getBigDecimal(3)),store,body.key());
            if (saved.size()!=items.size() || saved.stream().anyMatch(line->{
                Item requested=items.get(line.id());
                return requested==null || requested.quantity()!=line.quantity() || requested.price().compareTo(line.price())!=0;
            })) throw new ResponseStatusException(HttpStatus.CONFLICT);
        } else {
            for (var item : items.values()) {
                int changed=db.update("""
                        UPDATE product_variant SET stock_reserved=coalesce(stock_reserved,0)+?
                        WHERE tenant_id=? AND id=? AND price=? AND coalesce(stock_physical,0)-coalesce(stock_reserved,0)>=?
                        """,item.quantity(),store,item.id(),item.price(),item.quantity());
                if (changed!=1) throw new ResponseStatusException(HttpStatus.CONFLICT);
                db.update("""
                        INSERT INTO cart_reservation_line(tenant_id,reservation_id,variant_id,quantity,unit_price)
                        VALUES (?,?,?,?,?)
                        """,store,body.key(),item.id(),item.quantity(),item.price());
            }
        }
        return receipts.get(0);
    }
}
