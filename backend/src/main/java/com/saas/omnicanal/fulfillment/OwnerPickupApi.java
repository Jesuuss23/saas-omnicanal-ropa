package com.saas.omnicanal.fulfillment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/owner/stores/{store}/pickup")
class OwnerPickupApi {
    private final JdbcTemplate db;
    OwnerPickupApi(JdbcTemplate db) { this.db = db; }

    record Settings(@NotNull Boolean enabled, @NotNull @Size(max=250) String address,
                    @NotNull @Size(max=160) String hours, @NotNull @PositiveOrZero Long revision,
                    @PositiveOrZero @Digits(integer=8,fraction=2) BigDecimal freeDeliveryMinimum) {}

    private static void requireOwner(UUID store, Principal owner) {
        if (owner == null || !store.toString().equals(owner.getName()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    @GetMapping
    Settings read(@PathVariable UUID store, Principal owner) {
        requireOwner(store,owner);
        return db.query("""
                SELECT pickup_enabled,pickup_address,pickup_hours,pickup_revision,free_delivery_minimum FROM tenant WHERE id=?
                """,(rs,n)->new Settings(rs.getBoolean(1),rs.getString(2),rs.getString(3),rs.getLong(4),rs.getBigDecimal(5)),store)
                .stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PutMapping
    Settings save(@PathVariable UUID store, Principal owner, @Valid @RequestBody Settings input) {
        requireOwner(store,owner);
        String address=input.address().strip();
        String hours=input.hours().strip();
        if (input.enabled() && (address.isBlank() || hours.isBlank()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        int changed=db.update("""
                UPDATE tenant SET pickup_enabled=?,pickup_address=?,pickup_hours=?,free_delivery_minimum=?,pickup_revision=pickup_revision+1
                WHERE id=? AND pickup_revision=?
                """,input.enabled(),address,hours,input.freeDeliveryMinimum(),store,input.revision());
        if (changed==0) throw new ResponseStatusException(HttpStatus.CONFLICT);
        return new Settings(input.enabled(),address,hours,input.revision()+1,input.freeDeliveryMinimum());
    }
}
