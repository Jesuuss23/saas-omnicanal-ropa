package com.saas.omnicanal.tenant.controller;

import static com.saas.omnicanal.shared.security.OwnerAccess.requireOwner;

import com.saas.omnicanal.tenant.service.StoreOverviewService;
import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StoreOverviewController {
    private final StoreOverviewService overview;

    StoreOverviewController(StoreOverviewService overview) {
        this.overview = overview;
    }

    @GetMapping("/api/owner/stores/{store}/overview")
    StoreOverviewService.Overview overview(@PathVariable UUID store, Principal principal) {
        requireOwner(store, principal);
        return overview.overview(store);
    }
}
