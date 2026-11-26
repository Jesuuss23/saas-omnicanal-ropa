package com.saas.omnicanal.checkout.controller;

import com.saas.omnicanal.checkout.service.OrderTrackingTokens;

import com.saas.omnicanal.checkout.repository.WebOrderItemRepository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
class OrderTrackingController {
    private final JdbcTemplate db;
    private final WebOrderItemRepository items;
    private final com.saas.omnicanal.tenant.repository.AppearanceRepository appearance;

    OrderTrackingController(JdbcTemplate db, WebOrderItemRepository items, com.saas.omnicanal.tenant.repository.AppearanceRepository appearance) {
        this.db = db;
        this.items = items;
        this.appearance = appearance;
    }

    /** Marca de la tienda para que el enlace del pedido se vea como la tienda (nombre, color, logo y WhatsApp). */
    record Store(String name, String primaryColor, Integer logoVersion, String whatsappPhone, String logoUrl) {}

    record Pickup(String address, String hours) {}
    record Header(UUID reservation, String status, BigDecimal total, boolean freeDelivery, BigDecimal deliveryFee, Pickup pickup, String method, Instant reservationExpiresAt) {}
    record Detail(String status, BigDecimal total, List<WebOrderItemRepository.Item> items, boolean freeDelivery, BigDecimal deliveryFee, Pickup pickup, String method, Instant reservationExpiresAt, Store store) {}

    @GetMapping("/api/public/stores/{store}/orders/{order}")
    ResponseEntity<Detail> read(@PathVariable UUID store, @PathVariable UUID order,
            @RequestHeader(value="X-Order-Token",defaultValue="") String supplied) {
        if (supplied.length()!=64) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Header header=db.query("""
                SELECT o.reservation_id,
                       CASE WHEN o.status='PENDING_PAYMENT' AND r.expires_at<=CURRENT_TIMESTAMP
                            THEN 'EXPIRED' ELSE o.status END AS effective_status,
                       o.merchandise_total,o.free_delivery,o.delivery_fee,
                       CASE WHEN o.delivery_method='PICKUP' THEN o.fulfillment_address END AS pickup_address,
                       o.pickup_hours, o.delivery_method, r.expires_at
                FROM web_checkout_order o JOIN cart_reservation r
                  ON (r.tenant_id,r.id)=(o.tenant_id,o.reservation_id)
                WHERE o.tenant_id=? AND o.id=?
                """,(rs,n)->new Header(rs.getObject(1,UUID.class),rs.getString(2),rs.getBigDecimal(3),rs.getBoolean(4),rs.getBigDecimal(5),
                        rs.getString("pickup_address")==null ? null : new Pickup(rs.getString("pickup_address"),rs.getString("pickup_hours")),rs.getString("delivery_method"),
                        rs.getTimestamp("expires_at").toInstant()),store,order)
                .stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        String expected=OrderTrackingTokens.token(store,order,header.reservation());
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),supplied.getBytes(StandardCharsets.US_ASCII)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Detail(header.status(),header.total(),items.list(store,order),header.freeDelivery(),header.deliveryFee(),header.pickup(),header.method(),header.reservationExpiresAt(),storeOf(store)));
    }

    private Store storeOf(UUID store) {
        String name=db.query("SELECT name FROM tenant WHERE id=?",(rs,n)->rs.getString(1),store).stream().findFirst().orElse("");
        return appearance.find(store).map(a->new Store(name,a.primaryColor(),a.hasLogo() ? a.logoVersion() : null,a.whatsappPhone(),a.logoUrl()))
                .orElse(new Store(name,null,null,null,null));
    }
}
