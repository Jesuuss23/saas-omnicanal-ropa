package com.saas.omnicanal.tenant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/superadmin/tenants")
public class TenantRegistrationApi {
    private final JdbcTemplate db;

    TenantRegistrationApi(JdbcTemplate db) {
        this.db = db;
    }

    record Registration(@NotNull UUID id, @NotBlank @Size(max = 100) String name,
                        @NotBlank @Email @Size(max = 254) String ownerEmail) {}
    record Store(UUID id, String name, String ownerEmail) {}

    @PostMapping
    @Transactional
    public ResponseEntity<Store> register(@Valid @RequestBody Registration request) {
        var store = new Store(request.id(), request.name().strip(), request.ownerEmail().strip().toLowerCase(Locale.ROOT));
        int inserted = db.update("INSERT INTO tenant(id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                store.id(), store.name());
        if (inserted == 1) {
            db.update("INSERT INTO tenant_owner(tenant_id, email) VALUES (?, ?)", store.id(), store.ownerEmail());
            return ResponseEntity.status(HttpStatus.CREATED).body(store);
        }
        boolean sameRegistration = Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM tenant t JOIN tenant_owner o ON o.tenant_id = t.id
                  WHERE t.id = ? AND t.name = ? AND o.email = ?)
                """, Boolean.class, store.id(), store.name(), store.ownerEmail()));
        if (sameRegistration) return ResponseEntity.ok(store);
        throw new ResponseStatusException(HttpStatus.CONFLICT, "El identificador corresponde a otra tienda");
    }
}
