package com.saas.omnicanal.whatsapp.controller;

import com.saas.omnicanal.whatsapp.service.CloudApiService;
import com.saas.omnicanal.whatsapp.service.CloudWebhookService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/** URL de webhook que el dueño configura en su app de Meta: /api/webhooks/whatsapp-cloud/{tenantId}. */
@RestController
@RequestMapping(CloudApiService.WEBHOOK_ROOT + "/{store}")
class CloudApiWebhookController {
    private final CloudWebhookService webhooks;

    CloudApiWebhookController(CloudWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    String verify(@PathVariable UUID store, @RequestParam(name = "hub.mode", required = false) String mode,
                  @RequestParam(name = "hub.verify_token", required = false) String token,
                  @RequestParam(name = "hub.challenge", required = false) String challenge) {
        return webhooks.verifySubscription(store, mode, token, challenge);
    }

    /** Se recibe el cuerpo crudo: la firma se calcula sobre los bytes exactos que envió Meta. */
    @PostMapping(consumes = MediaType.ALL_VALUE)
    Map<String, Integer> receive(@PathVariable UUID store, @RequestBody byte[] body,
                                 @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) {
        return webhooks.receive(store, body, signature);
    }
}
