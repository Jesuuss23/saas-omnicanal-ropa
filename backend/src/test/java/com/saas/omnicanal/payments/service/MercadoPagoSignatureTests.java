package com.saas.omnicanal.payments.service;

import static org.junit.jupiter.api.Assertions.*;

import com.saas.omnicanal.shared.crypto.Hmac;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MercadoPagoSignatureTests {
    @Test
    void validatesTheDocumentedManifest() {
        String secret = "mp-secret";
        String v1 = Hmac.sha256Hex(secret, "id:123456;request-id:req-9;ts:1704908010;".getBytes(StandardCharsets.UTF_8));
        String header = "ts=1704908010,v1=" + v1;
        assertTrue(MercadoPagoWebhookService.validSignature(secret, header, "req-9", "123456"));
        assertFalse(MercadoPagoWebhookService.validSignature(secret, header, "req-9", "999"));
        assertFalse(MercadoPagoWebhookService.validSignature("otro", header, "req-9", "123456"));
        assertFalse(MercadoPagoWebhookService.validSignature(secret, null, "req-9", "123456"));
    }
}
