package com.saas.omnicanal.checkout.controller;

import com.saas.omnicanal.checkout.service.OrderTrackingTokens;

import com.saas.omnicanal.checkout.repository.WebOrderItemRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import jakarta.validation.constraints.*;
import java.util.List;
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
class GuestCheckoutController {
    private final JdbcTemplate db;

    private final WebOrderItemRepository orderItems;

    GuestCheckoutController(JdbcTemplate db, WebOrderItemRepository orderItems) {
        this.db = db;
        this.orderItems = orderItems;
    }

    record Contact(@NotBlank @Size(max=150) String name,
                   @NotBlank @Pattern(regexp="[+]?[0-9]{7,15}") String phone) {}

    record Order(UUID id, BigDecimal total, List<WebOrderItemRepository.Item> items, String trackingToken, boolean freeDelivery, BigDecimal deliveryFee, String method) {}
    record Header(UUID id, BigDecimal total, boolean freeDelivery, BigDecimal deliveryFee, String method) {}
    record GuestRow(String name, String phone, UUID orderId, BigDecimal total, boolean freeDelivery, BigDecimal minimum, BigDecimal subtotal, BigDecimal fee, boolean distancePricing, String method) {}
    record Pickup(String address, String hours, long revision) {}
    record Checkout(String name, String phone, Order order, Pickup pickup, BigDecimal freeDeliveryMinimum, BigDecimal subtotal, boolean distancePricing) {}
    enum Method { PICKUP, DELIVERY }
    record Choice(Method method, @Size(max=250) String address, Long pickupRevision, BigDecimal freeDeliveryMinimum, @Valid Point point, BigDecimal expectedFee) {}

    record Point(@NotNull @DecimalMin("-90") @DecimalMax("90") @Digits(integer=3,fraction=6) BigDecimal latitude,
                 @NotNull @DecimalMin("-180") @DecimalMax("180") @Digits(integer=3,fraction=6) BigDecimal longitude) {}
    record Quote(BigDecimal fee) {}

    private BigDecimal fee(UUID store, UUID key, Point point) {
        return db.queryForObject("""
                SELECT CASE WHEN sum(l.unit_price*l.quantity)>=t.free_delivery_minimum THEN 0
                    WHEN t.delivery_per_km IS NULL THEN NULL
                    ELSE round((t.delivery_per_km * 12742 * asin(sqrt(least(1.0,
                      power(sin(radians(? - t.dispatch_lat)/2),2) + cos(radians(t.dispatch_lat))*cos(radians(?))*
                      power(sin(radians(? - t.dispatch_lon)/2),2)))))::numeric,2) END
                FROM tenant t JOIN cart_reservation_line l ON l.tenant_id=t.id
                WHERE t.id=? AND l.reservation_id=? GROUP BY t.id
                """,BigDecimal.class,point.latitude(),point.latitude(),point.longitude(),store,key);
    }

    @PostMapping("/quote")
    @Transactional
    Quote quote(@PathVariable UUID store,@RequestHeader("X-Reservation-Key") UUID key,@Valid @RequestBody Point point) {
        lockActive(store,key);
        return new Quote(fee(store,key,point));
    }

    private void lockActive(UUID store, UUID key) {
        var active = db.query("""
                SELECT NOT released AND expires_at>clock_timestamp() FROM cart_reservation
                WHERE tenant_id=? AND id=? FOR UPDATE
                """, (rs,n)->rs.getBoolean(1), store,key);
        if (active.isEmpty() || !active.get(0)) throw new ResponseStatusException(HttpStatus.GONE);
    }

    @PostMapping
    @Transactional
    ResponseEntity<Order> confirm(@PathVariable UUID store, @RequestHeader("X-Reservation-Key") UUID key,
                                  @Valid @RequestBody(required=false) Choice choice) {
        lockActive(store,key);
        var existing=db.query("SELECT id,merchandise_total,free_delivery,delivery_fee,delivery_method FROM web_checkout_order WHERE tenant_id=? AND reservation_id=?",
                (rs,n)->new Header(rs.getObject(1,UUID.class),rs.getBigDecimal(2),rs.getBoolean(3),rs.getBigDecimal(4),rs.getString(5)),store,key);
        if (!existing.isEmpty()) {
            Header prior=existing.get(0);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(order(store,key,prior.id(),prior.total(),prior.freeDelivery(),prior.deliveryFee(),prior.method()));
        }
        if (choice==null || choice.method()==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String address=choice.address()==null ? "" : choice.address().strip();
        String hours=null;
        if (choice.method()==Method.PICKUP) {
            var pickup=db.query("""
                    SELECT pickup_address,pickup_hours,pickup_revision FROM tenant
                    WHERE id=? AND pickup_enabled AND pickup_revision=? FOR SHARE
                    """,(rs,n)->new Pickup(rs.getString(1),rs.getString(2),rs.getLong(3)),store,choice.pickupRevision())
                    .stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT));
            address=pickup.address();
            hours=pickup.hours();
        } else if (address.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        BigDecimal charge=null;
        Point destination=null;
        if (choice.method()==Method.DELIVERY) {
            boolean needsQuote=Boolean.TRUE.equals(db.queryForObject("""
                    SELECT delivery_per_km IS NOT NULL AND NOT coalesce(
                        (SELECT sum(quantity*unit_price) FROM cart_reservation_line WHERE tenant_id=t.id AND reservation_id=?)>=free_delivery_minimum,false)
                    FROM tenant t WHERE id=? FOR SHARE
                    """,Boolean.class,key,store));
            if (needsQuote) {
                destination=choice.point();
                if (destination==null || choice.expectedFee()==null) throw new ResponseStatusException(HttpStatus.CONFLICT);
                charge=fee(store,key,destination);
                if (charge==null || charge.compareTo(choice.expectedFee())!=0) throw new ResponseStatusException(HttpStatus.CONFLICT);
            } else if (choice.expectedFee()!=null && choice.expectedFee().signum()!=0) throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        Header header=db.query("""
                INSERT INTO web_checkout_order(tenant_id,reservation_id,guest_name,guest_phone,merchandise_total,
                                               delivery_method,fulfillment_address,pickup_hours,delivery_fee,destination_lat,destination_lon,free_delivery)
                SELECT r.tenant_id,r.id,r.guest_name,r.guest_phone,sum(l.quantity*l.unit_price),?,?,?,?,?,?,
                       (?='DELIVERY' AND coalesce(sum(l.quantity*l.unit_price)>=t.free_delivery_minimum,false))
                FROM cart_reservation r JOIN tenant t ON t.id=r.tenant_id JOIN cart_reservation_line l
                  ON (l.tenant_id,l.reservation_id)=(r.tenant_id,r.id)
                WHERE r.tenant_id=? AND r.id=? AND r.guest_name IS NOT NULL
                  AND (? <> 'DELIVERY' OR t.free_delivery_minimum IS NOT DISTINCT FROM CAST(? AS numeric))
                GROUP BY r.tenant_id,r.id,t.free_delivery_minimum RETURNING id,merchandise_total,free_delivery,delivery_fee,delivery_method
                """,(rs,n)->new Header(rs.getObject(1,UUID.class),rs.getBigDecimal(2),rs.getBoolean(3),rs.getBigDecimal(4),rs.getString(5)),choice.method().name(),address,hours,charge,destination==null?null:destination.latitude(),destination==null?null:destination.longitude(),choice.method().name(),store,key,choice.method().name(),choice.freeDeliveryMinimum())
                .stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(order(store,key,header.id(),header.total(),header.freeDelivery(),header.deliveryFee(),header.method()));
    }

    private Order order(UUID store, UUID key, UUID id, BigDecimal total, boolean freeDelivery, BigDecimal deliveryFee, String method) {
        return new Order(id,total,orderItems.list(store,id),OrderTrackingTokens.token(store,id,key),freeDelivery,deliveryFee,method);
    }

    @GetMapping
    ResponseEntity<Checkout> read(@PathVariable UUID store, @RequestHeader("X-Reservation-Key") UUID key) {
        var contact = db.query("""
                SELECT r.guest_name,r.guest_phone,o.id,o.merchandise_total,o.free_delivery,t.free_delivery_minimum,
                       (SELECT sum(l.quantity*l.unit_price) FROM cart_reservation_line l WHERE (l.tenant_id,l.reservation_id)=(r.tenant_id,r.id)),o.delivery_fee,t.delivery_per_km IS NOT NULL,o.delivery_method
                FROM cart_reservation r JOIN tenant t ON t.id=r.tenant_id
                LEFT JOIN web_checkout_order o ON (o.tenant_id,o.reservation_id)=(r.tenant_id,r.id)
                WHERE r.tenant_id=? AND r.id=? AND NOT r.released AND r.expires_at>clock_timestamp()
                """, (rs, n) -> new GuestRow(rs.getString(1),rs.getString(2),rs.getObject(3,UUID.class),rs.getBigDecimal(4),rs.getBoolean(5),rs.getBigDecimal(6),rs.getBigDecimal(7),rs.getBigDecimal(8),rs.getBoolean(9),rs.getString(10)), store, key)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE));
        Pickup pickup=db.query("SELECT pickup_address,pickup_hours,pickup_revision FROM tenant WHERE id=? AND pickup_enabled",
                (rs,n)->new Pickup(rs.getString(1),rs.getString(2),rs.getLong(3)),store).stream().findFirst().orElse(null);
        Order detail=contact.orderId()==null ? null : order(store,key,contact.orderId(),contact.total(),contact.freeDelivery(),contact.fee(),contact.method());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Checkout(contact.name(),contact.phone(),detail,pickup,contact.minimum(),contact.subtotal(),contact.distancePricing()));
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
