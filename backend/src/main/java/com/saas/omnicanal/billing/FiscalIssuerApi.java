package com.saas.omnicanal.billing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/owner/stores/{store}/billing/issuer")
class FiscalIssuerApi {
    private final JdbcTemplate db;

    FiscalIssuerApi(JdbcTemplate db) {
        this.db = db;
    }

    record Profile(@NotBlank @Pattern(regexp="[0-9]{11}") String ruc,
                   @NotBlank @Size(max=200) String legalName,
                   @NotBlank @Size(max=250) String address,
                   @NotBlank @Pattern(regexp="[0-9]{6}") String ubigeo,
                   @NotNull @PositiveOrZero Integer revision) {}

    @GetMapping
    Profile get(@PathVariable UUID store, Principal owner) {
        requireOwner(store, owner);
        return db.query("""
                SELECT ruc, legal_name, address, ubigeo, revision
                FROM tenant_fiscal_issuer WHERE tenant_id = ?
                """, (rs, row) -> new Profile(rs.getString("ruc"), rs.getString("legal_name"),
                rs.getString("address"), rs.getString("ubigeo"), rs.getInt("revision")), store)
                .stream().findFirst().orElse(new Profile("", "", "", "", 0));
    }

    @PutMapping
    Profile save(@PathVariable UUID store, Principal owner, @Valid @RequestBody Profile input) {
        requireOwner(store, owner);
        String name = input.legalName().strip();
        String address = input.address().strip();
        int changed;
        if (input.revision() == 0) {
            changed = db.update("""
                    INSERT INTO tenant_fiscal_issuer(tenant_id,ruc,legal_name,address,ubigeo,revision)
                    VALUES (?,?,?,?,?,1) ON CONFLICT (tenant_id) DO NOTHING
                    """, store, input.ruc(), name, address, input.ubigeo());
        } else {
            changed = db.update("""
                    UPDATE tenant_fiscal_issuer SET ruc=?,legal_name=?,address=?,ubigeo=?,revision=revision+1
                    WHERE tenant_id=? AND revision=?
                    """, input.ruc(), name, address, input.ubigeo(), store, input.revision());
        }
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT);
        return new Profile(input.ruc(), name, address, input.ubigeo(), input.revision()+1);
    }

    private static void requireOwner(UUID store, Principal owner) {
        if (!store.toString().equals(owner.getName())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
}
