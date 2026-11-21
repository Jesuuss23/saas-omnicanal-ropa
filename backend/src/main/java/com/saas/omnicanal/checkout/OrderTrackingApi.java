package com.saas.omnicanal.checkout;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
class OrderTrackingApi {
    private final JdbcTemplate db;
    private final WebOrderItems items;

    OrderTrackingApi(JdbcTemplate db, WebOrderItems items) {
        this.db = db;
        this.items = items;
    }

    record Pickup(String address, String hours) {}
    record Header(UUID reservation, String status, BigDecimal total, boolean freeDelivery, BigDecimal deliveryFee, Pickup pickup) {}
    record Detail(String status, BigDecimal total, List<WebOrderItems.Item> items, boolean freeDelivery, BigDecimal deliveryFee, Pickup pickup) {}

    static String token(UUID store, UUID order, UUID reservation) {
        String input = "order-tracking-v1/"+store+"/"+order+"/"+reservation;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    @GetMapping("/api/public/stores/{store}/orders/{order}")
    ResponseEntity<Detail> read(@PathVariable UUID store, @PathVariable UUID order,
            @RequestHeader(value="X-Order-Token",defaultValue="") String supplied) {
        if (supplied.length()!=64) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Header header=db.query("""
                SELECT reservation_id,status,merchandise_total,free_delivery,delivery_fee,
                       CASE WHEN delivery_method='PICKUP' THEN fulfillment_address END AS pickup_address,
                       pickup_hours
                FROM web_checkout_order WHERE tenant_id=? AND id=?
                """,(rs,n)->new Header(rs.getObject(1,UUID.class),rs.getString(2),rs.getBigDecimal(3),rs.getBoolean(4),rs.getBigDecimal(5),
                        rs.getString("pickup_address")==null ? null : new Pickup(rs.getString("pickup_address"),rs.getString("pickup_hours"))),store,order)
                .stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        String expected=token(store,order,header.reservation());
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),supplied.getBytes(StandardCharsets.US_ASCII)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Detail(header.status(),header.total(),items.list(store,order),header.freeDelivery(),header.deliveryFee(),header.pickup()));
    }
}
