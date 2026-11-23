package com.saas.omnicanal.subscription.controller;

import com.saas.omnicanal.subscription.service.CulqiWebhookService;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/** URL a registrar en CulqiPanel: {PUBLIC_API_BASE_URL}/api/webhooks/culqi/{CULQI_WEBHOOK_TOKEN}. */
@RestController
class CulqiWebhookController {
    private final CulqiWebhookService webhooks;

    CulqiWebhookController(CulqiWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping(path = "/api/webhooks/culqi/{token}", consumes = MediaType.ALL_VALUE)
    Map<String, String> receive(@PathVariable String token, @RequestBody String body) {
        return webhooks.receive(token, body);
    }
}
