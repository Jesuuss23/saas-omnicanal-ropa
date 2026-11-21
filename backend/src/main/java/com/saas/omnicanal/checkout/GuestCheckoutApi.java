package com.saas.omnicanal.checkout;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
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

    @GetMapping
    ResponseEntity<Contact> read(@PathVariable UUID store, @RequestHeader("X-Reservation-Key") UUID key) {
        var contact = db.query("""
                SELECT guest_name, guest_phone FROM cart_reservation
                WHERE tenant_id=? AND id=? AND NOT released AND expires_at>clock_timestamp()
                """, (rs, n) -> new Contact(rs.getString(1), rs.getString(2)), store, key)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(contact);
    }

    @PutMapping
    ResponseEntity<Contact> save(@PathVariable UUID store, @RequestHeader("X-Reservation-Key") UUID key,
                                 @Valid @RequestBody Contact input) {
        var contact = new Contact(input.name().strip(), input.phone());
        int changed = db.update("""
                UPDATE cart_reservation SET guest_name=?, guest_phone=?
                WHERE tenant_id=? AND id=? AND NOT released AND expires_at>clock_timestamp()
                """, contact.name(), contact.phone(), store, key);
        if (changed==0) throw new ResponseStatusException(HttpStatus.GONE);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(contact);
    }
}
