package com.saas.omnicanal.platformadmin.controller;

import com.saas.omnicanal.platformadmin.model.PageResult;
import com.saas.omnicanal.platformadmin.model.SubscriptionChange;
import com.saas.omnicanal.platformadmin.repository.AdminConsoleRepository.PaymentEvent;
import com.saas.omnicanal.platformadmin.repository.AdminConsoleRepository.TenantRow;
import com.saas.omnicanal.platformadmin.repository.AuditRepository;
import com.saas.omnicanal.platformadmin.service.AdminConsoleService;
import com.saas.omnicanal.platformadmin.service.AdminConsoleService.Dashboard;
import com.saas.omnicanal.platformadmin.service.AdminConsoleService.Detail;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** Consola del superadmin: resumen, tiendas, pagos y auditoría. */
@RestController
@RequestMapping("/api/superadmin")
class AdminConsoleController {
    private final AdminConsoleService console;

    AdminConsoleController(AdminConsoleService console) {
        this.console = console;
    }

    @GetMapping("/dashboard")
    Dashboard dashboard(Principal principal) {
        return console.dashboard(principal);
    }

    @GetMapping("/tenants")
    PageResult<TenantRow> tenants(@RequestParam(required = false) @Size(max = 100) String q,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return console.tenants(q, status, page, size);
    }

    @GetMapping("/tenants/{id}")
    Detail tenant(@PathVariable UUID id) {
        return console.tenant(id);
    }

    record Suspension(@NotBlank @Size(max = 300) String reason) {}

    @PostMapping("/tenants/{id}/suspend")
    Detail suspend(@PathVariable UUID id, @Valid @RequestBody Suspension input, Principal principal) {
        return console.suspend(principal, id, input.reason());
    }

    @PostMapping("/tenants/{id}/reactivate")
    Detail reactivate(@PathVariable UUID id, Principal principal) {
        return console.reactivate(principal, id);
    }

    @PutMapping("/tenants/{id}/subscription")
    Detail subscription(@PathVariable UUID id, @Valid @RequestBody SubscriptionChange input, Principal principal) {
        return console.changeSubscription(principal, id, input);
    }

    @GetMapping("/payments")
    PageResult<PaymentEvent> payments(@RequestParam(required = false) String provider, @RequestParam(required = false) String status,
                                      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return console.payments(provider, status, page, size);
    }

    @GetMapping("/audit")
    PageResult<AuditRepository.Entry> audit(@RequestParam(required = false) UUID tenant,
                                            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return console.audit(tenant, page, size);
    }
}
