package com.saas.omnicanal.payments.controller;

import com.saas.omnicanal.payments.service.MercadoPagoWebhookService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
class MercadoPagoWebhookController {
    private final MercadoPagoWebhookService webhooks;

    MercadoPagoWebhookController(MercadoPagoWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping(path = "/api/webhooks/mercadopago/{store}", consumes = MediaType.ALL_VALUE)
    Map<String, String> receive(@PathVariable UUID store,
                                @RequestParam(name = "type", required = false) String type,
                                @RequestParam(name = "topic", required = false) String topic,
                                @RequestParam(name = "data.id", required = false) String dataId,
                                @RequestParam(name = "id", required = false) String legacyId,
                                @RequestHeader(value = "x-signature", required = false) String signature,
                                @RequestHeader(value = "x-request-id", required = false) String requestId,
                                @RequestBody(required = false) String body) {
        String kind = type != null ? type : topic;
        String id = dataId != null ? dataId : legacyId;
        return webhooks.receive(store, kind, id, signature, requestId, body == null ? "" : body);
    }
}
