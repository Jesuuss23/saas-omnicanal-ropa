package com.saas.omnicanal.checkout;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/owner/stores/{store}/web-orders")
class OwnerWebOrdersApi {
    private final JdbcTemplate db;

    OwnerWebOrdersApi(JdbcTemplate db) { this.db = db; }

    enum Status { PENDING_PAYMENT, CANCELLED, EXPIRED }
    record Summary(UUID id, String name, String phone, BigDecimal total, String status, Instant createdAt) {}
    record Page(List<Summary> orders, boolean more) {}
    record Item(UUID variant, String name, String size, String color, int quantity, BigDecimal unitPrice) {}

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
                SELECT id, guest_name, guest_phone, merchandise_total, status, created_at
                FROM web_checkout_order WHERE tenant_id=? AND status=coalesce(?,status)
                ORDER BY created_at DESC,id DESC LIMIT 21 OFFSET ?
                """, (rs,n)->new Summary(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),
                rs.getBigDecimal(4),rs.getString(5),rs.getTimestamp(6).toInstant()),
                store,status==null ? null : status.name(),20L*page);
        return new Page(rows.stream().limit(20).toList(),rows.size()>20);
    }

    @GetMapping("/{order}/items")
    List<Item> items(@PathVariable UUID store, @PathVariable UUID order, Principal principal) {
        requireOwner(store, principal);
        var rows = db.query("""
                SELECT v.id,p.name,v.size,v.color,l.quantity,l.unit_price
                FROM web_checkout_order o JOIN cart_reservation_line l
                  ON (l.tenant_id,l.reservation_id)=(o.tenant_id,o.reservation_id)
                JOIN product_variant v ON (v.tenant_id,v.id)=(l.tenant_id,l.variant_id)
                JOIN product p ON (p.tenant_id,p.id)=(v.tenant_id,v.product_id)
                WHERE o.tenant_id=? AND o.id=? ORDER BY p.name,v.size,v.color,v.id
                """,(rs,n)->new Item(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getInt(5),rs.getBigDecimal(6)),store,order);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return rows;
    }
}
