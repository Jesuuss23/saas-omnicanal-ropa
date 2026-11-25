package com.saas.omnicanal.fiscal.controller;

import com.saas.omnicanal.fiscal.model.FiscalProfile;

import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/owner/stores/{store}/billing/issuer")
class FiscalIssuerController {
    private final JdbcTemplate db;

    FiscalIssuerController(JdbcTemplate db) {
        this.db = db;
    }


    @GetMapping
    FiscalProfile get(@PathVariable UUID store, Principal owner) {
        requireOwner(store, owner);
        return db.query("""
                SELECT ruc, legal_name, address, ubigeo, revision
                FROM tenant_fiscal_issuer WHERE tenant_id = ?
                """, (rs, row) -> new FiscalProfile(rs.getString("ruc"), rs.getString("legal_name"),
                rs.getString("address"), rs.getString("ubigeo"), rs.getInt("revision")), store)
                .stream().findFirst().orElse(new FiscalProfile("", "", "", "", 0));
    }

    @PutMapping
    FiscalProfile save(@PathVariable UUID store, Principal owner, @Valid @RequestBody FiscalProfile input) {
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
        return new FiscalProfile(input.ruc(), name, address, input.ubigeo(), input.revision()+1);
    }

    private static void requireOwner(UUID store, Principal owner) {
        if (!store.toString().equals(owner.getName())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
}
