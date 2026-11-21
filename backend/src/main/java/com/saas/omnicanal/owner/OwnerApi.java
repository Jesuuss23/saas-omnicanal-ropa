package com.saas.omnicanal.owner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.Principal;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
class OwnerApi {
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;
    private final SecureRandom random = new SecureRandom();

    OwnerApi(JdbcTemplate db, PasswordEncoder passwords) {
        this.db = db;
        this.passwords = passwords;
    }

    record Store(UUID id, String name, String email, boolean active) {}
    record Activation(@NotNull UUID store, @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token,
                      @NotNull @Size(min = 12, max = 72) String password) {}

    @GetMapping("/api/superadmin/owners")
    List<Store> find(@RequestParam String email) {
        return db.query("""
                SELECT t.id, t.name, o.email, o.password_hash IS NOT NULL AS active
                FROM tenant_owner o JOIN tenant t ON t.id = o.tenant_id
                WHERE lower(o.email) = lower(?) ORDER BY t.name, t.id
                """, (rs, row) -> new Store(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("email"), rs.getBoolean("active")), email.strip());
    }

    @PostMapping("/api/superadmin/owners/{id}/invitation")
    Map<String, String> invite(@PathVariable UUID id) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        int count = db.update("""
                UPDATE tenant_owner SET invitation_hash = ?, invitation_expires_at = now() + interval '24 hours'
                WHERE tenant_id = ? AND password_hash IS NULL
                """, digest(token), id);
        if (count != 1) throw new ResponseStatusException(HttpStatus.CONFLICT);
        return Map.of("token", token);
    }

    @PostMapping("/api/owner/activate")
    Map<String, Boolean> activate(@Valid @RequestBody Activation input) {
        if (input.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        String tokenHash = digest(input.token());
        String condition = " WHERE tenant_id = ? AND invitation_hash = ? AND invitation_expires_at > now() AND password_hash IS NULL";
        boolean valid = Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM tenant_owner" + condition + ")",
                Boolean.class, input.store(), tokenHash));
        if (!valid) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        int changed = db.update("UPDATE tenant_owner SET password_hash = ?, invitation_hash = NULL, invitation_expires_at = NULL"
                + condition, passwords.encode(input.password()), input.store(), tokenHash);
        if (changed != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return Map.of("activated", true);
    }

    @GetMapping("/api/owner/csrf")
    Map<String, String> csrf(CsrfToken csrf) {
        return Map.of("token", csrf.getToken());
    }

    @GetMapping("/api/owner/me")
    Store profile(Principal principal) {
        return db.queryForObject("""
                SELECT t.name, o.email FROM tenant t JOIN tenant_owner o ON o.tenant_id = t.id WHERE t.id = ?
                """, (rs, row) -> new Store(UUID.fromString(principal.getName()), rs.getString("name"),
                rs.getString("email"), true), UUID.fromString(principal.getName()));
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
