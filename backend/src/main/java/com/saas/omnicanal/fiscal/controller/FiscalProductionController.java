package com.saas.omnicanal.fiscal.controller;

import com.saas.omnicanal.fiscal.model.ProductionSetup;
import com.saas.omnicanal.fiscal.service.FiscalProductionService;
import com.saas.omnicanal.team.model.StoreSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import static com.saas.omnicanal.shared.security.OwnerAccess.requireOwner;

/** Alta fiscal para producción en 4 pasos. Solo el propietario (secretos y activación, ver OwnerSecurity). */
@RestController
@RequestMapping("/api/owner/stores/{store}/billing/production")
class FiscalProductionController {
    private final FiscalProductionService production;

    FiscalProductionController(FiscalProductionService production) {
        this.production = production;
    }

    record Sol(@NotNull @Size(max = 20) String user, @NotNull @Size(max = 50) String password, @Min(0) long revision) {}
    record Certificate(@NotNull @Size(max = 90000) String pfxBase64, @NotNull @Size(max = 100) String password, @Min(0) long revision) {}
    record Series(@NotNull @Size(max = 4) String invoice, @NotNull @Size(max = 4) String receipt, @Min(0) long revision) {}
    record Activate(@NotNull @Size(max = 11) String ruc, @Min(1) long revision) {}
    record Deactivate(@Min(1) long revision) {}

    @GetMapping
    ResponseEntity<ProductionSetup> get(@PathVariable UUID store, Authentication auth) {
        requireOwner(store, auth);
        return ok(production.setup(store));
    }

    @PutMapping("/sol")
    ResponseEntity<ProductionSetup> sol(@PathVariable UUID store, Authentication auth, @Valid @RequestBody Sol in) {
        requireOwner(store, auth);
        return ok(production.saveSol(store, in.revision(), in.user(), in.password()));
    }

    @PutMapping("/certificate")
    ResponseEntity<ProductionSetup> certificate(@PathVariable UUID store, Authentication auth, @Valid @RequestBody Certificate in) {
        requireOwner(store, auth);
        return ok(production.saveCertificate(store, in.revision(), in.pfxBase64(), in.password()));
    }

    @PutMapping("/series")
    ResponseEntity<ProductionSetup> series(@PathVariable UUID store, Authentication auth, @Valid @RequestBody Series in) {
        requireOwner(store, auth);
        return ok(production.saveSeries(store, in.revision(), in.invoice(), in.receipt()));
    }

    @PostMapping("/activate")
    ResponseEntity<ProductionSetup> activate(@PathVariable UUID store, Authentication auth, @Valid @RequestBody Activate in) {
        requireOwner(store, auth);
        String by = auth.getDetails() instanceof StoreSession s && s.email() != null ? s.email() : "propietario";
        return ok(production.activate(store, in.revision(), in.ruc(), by));
    }

    @PostMapping("/deactivate")
    ResponseEntity<ProductionSetup> deactivate(@PathVariable UUID store, Authentication auth, @Valid @RequestBody Deactivate in) {
        requireOwner(store, auth);
        return ok(production.deactivate(store, in.revision()));
    }

    private static ResponseEntity<ProductionSetup> ok(ProductionSetup body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
