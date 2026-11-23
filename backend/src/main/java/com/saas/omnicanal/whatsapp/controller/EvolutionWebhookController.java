package com.saas.omnicanal.whatsapp.controller;

import com.saas.omnicanal.whatsapp.service.EvolutionWebhookService;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
class EvolutionWebhookController {
    private final EvolutionWebhookService webhooks;

    EvolutionWebhookController(EvolutionWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping(EvolutionWebhookService.ROOT + "/{store}")
    Map<String, Integer> receive(@PathVariable UUID store,
                                 @RequestHeader(value = EvolutionWebhookService.HEADER, defaultValue = "") String key,
                                 @RequestBody JsonNode event) {
        return webhooks.receive(store, key, event);
    }
}
