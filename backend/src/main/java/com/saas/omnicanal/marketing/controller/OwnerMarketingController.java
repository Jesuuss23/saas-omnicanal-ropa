package com.saas.omnicanal.marketing.controller;

import com.saas.omnicanal.marketing.model.MarketingModels.AbandonedSettings;
import com.saas.omnicanal.marketing.model.MarketingModels.Campaign;
import com.saas.omnicanal.marketing.model.MarketingModels.Channel;
import com.saas.omnicanal.marketing.model.MarketingModels.Segment;
import com.saas.omnicanal.marketing.service.MarketingService;
import com.saas.omnicanal.marketing.service.MarketingService.Draft;
import com.saas.omnicanal.marketing.service.MarketingService.Overview;
import com.saas.omnicanal.marketing.service.MarketingService.Preview;
import com.saas.omnicanal.team.model.StoreSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import static com.saas.omnicanal.shared.security.OwnerAccess.requireOwner;

/** Campañas y carrito abandonado (propietario o encargado, ver OwnerSecurity). */
@RestController
@RequestMapping("/api/owner/stores/{store}/marketing")
class OwnerMarketingController {
    private final MarketingService marketing;

    OwnerMarketingController(MarketingService marketing) {
        this.marketing = marketing;
    }

    record SegmentIn(@Size(max = 12) String buyers, Integer lastOrderWithinDays, Integer noOrderForDays, java.math.BigDecimal minSpent) {
        Segment toSegment() {
            return new Segment(buyers, lastOrderWithinDays, noOrderForDays, minSpent);
        }
    }
    record PreviewIn(@NotNull Channel channel, @Valid SegmentIn segment) {}
    record CampaignIn(@NotNull @Size(max = 120) String name, @NotNull Channel channel, @Valid SegmentIn segment,
                      @Size(max = 150) String subject, @Size(max = 5000) String body,
                      @Size(max = 512) String templateName, @Size(max = 15) String templateLang, @Min(0) long revision) {
        Draft toDraft() {
            return new Draft(name, channel, segment == null ? null : segment.toSegment(), subject, body, templateName, templateLang);
        }
    }
    record Launch(@Min(1) long revision) {}
    record AbandonedIn(@NotNull Boolean enabled, @Min(15) @Max(1440) int delayMinutes, @Size(max = 512) String templateName,
                       @Size(max = 15) String templateLang, @Min(0) long revision) {}

    @GetMapping
    Overview overview(@PathVariable UUID store, Authentication auth) {
        requireOwner(store, auth);
        return marketing.overview(store);
    }

    @PostMapping("/preview")
    Preview preview(@PathVariable UUID store, Authentication auth, @Valid @RequestBody PreviewIn in) {
        requireOwner(store, auth);
        return marketing.preview(store, in.channel(), in.segment() == null ? null : in.segment().toSegment());
    }

    @PostMapping("/campaigns")
    Campaign create(@PathVariable UUID store, Authentication auth, @Valid @RequestBody CampaignIn in) {
        requireOwner(store, auth);
        return marketing.create(store, in.toDraft(), actor(auth));
    }

    @PutMapping("/campaigns/{id}")
    Campaign update(@PathVariable UUID store, @PathVariable UUID id, Authentication auth, @Valid @RequestBody CampaignIn in) {
        requireOwner(store, auth);
        return marketing.update(store, id, in.revision(), in.toDraft());
    }

    @PostMapping("/campaigns/{id}/launch")
    Campaign launch(@PathVariable UUID store, @PathVariable UUID id, Authentication auth, @Valid @RequestBody Launch in) {
        requireOwner(store, auth);
        return marketing.launch(store, id, in.revision());
    }

    @PostMapping("/campaigns/{id}/cancel")
    Campaign cancel(@PathVariable UUID store, @PathVariable UUID id, Authentication auth) {
        requireOwner(store, auth);
        return marketing.cancel(store, id);
    }

    @PutMapping("/abandoned-cart")
    AbandonedSettings abandoned(@PathVariable UUID store, Authentication auth, @Valid @RequestBody AbandonedIn in) {
        requireOwner(store, auth);
        return marketing.saveAbandoned(store, in.revision(), in.enabled(), in.delayMinutes(), in.templateName(), in.templateLang());
    }

    private static String actor(Authentication auth) {
        return auth.getDetails() instanceof StoreSession s && s.email() != null ? s.email() : "propietario";
    }
}
