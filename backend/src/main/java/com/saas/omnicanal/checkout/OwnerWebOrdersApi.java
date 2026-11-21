package com.saas.omnicanal.checkout;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/owner/stores/{store}/web-orders")
class OwnerWebOrdersApi {
    private final JdbcTemplate db;

    private final WebOrderItems orderItems;

    OwnerWebOrdersApi(JdbcTemplate db, WebOrderItems orderItems) {
        this.db = db;
        this.orderItems = orderItems;
    }

    enum Status { PENDING_PAYMENT, CANCELLED, EXPIRED }
    record Summary(UUID id, String name, String phone, BigDecimal total, String status, Instant createdAt, String method, String address, String pickupHours) {}
    record Page(List<Summary> orders, boolean more) {}

    private static void requireOwner(UUID store, Principal principal) {
        if (principal == null || !store.toString().equals(principal.getName()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    @GetMapping
    Page list(@PathVariable UUID store, Principal principal, @RequestParam(defaultValue="0") int page,
              @RequestParam(required=false) Status status) {
        requireOwner(store, principal);
        if (page < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var rows = db.query("""
                SELECT id, guest_name, guest_phone, merchandise_total, status, created_at, delivery_method, fulfillment_address, pickup_hours
                FROM web_checkout_order WHERE tenant_id=? AND status=coalesce(?,status)
                ORDER BY created_at DESC,id DESC LIMIT 21 OFFSET ?
                """, (rs,n)->new Summary(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),
                rs.getBigDecimal(4),rs.getString(5),rs.getTimestamp(6).toInstant(),rs.getString(7),rs.getString(8),rs.getString(9)),
                store,status==null ? null : status.name(),20L*page);
        return new Page(rows.stream().limit(20).toList(),rows.size()>20);
    }

    record Tracking(String token) {}

    @GetMapping("/{order}/tracking")
    ResponseEntity<Tracking> tracking(@PathVariable UUID store, @PathVariable UUID order, Principal principal) {
        requireOwner(store, principal);
        UUID reservation = db.query("""
                SELECT reservation_id FROM web_checkout_order WHERE tenant_id=? AND id=?
                """, (rs,n)->rs.getObject(1,UUID.class),store,order)
                .stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Tracking(OrderTrackingApi.token(store,order,reservation)));
    }

    @GetMapping("/{order}/items")
    List<WebOrderItems.Item> items(@PathVariable UUID store, @PathVariable UUID order, Principal principal) {
        requireOwner(store, principal);
        var rows = orderItems.list(store, order);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows;
    }
}
