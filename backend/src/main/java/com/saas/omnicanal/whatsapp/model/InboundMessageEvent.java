package com.saas.omnicanal.whatsapp.model;

import java.util.UUID;

/**
 * Se publica una sola vez por mensaje entrante nuevo (los reenvíos del proveedor no generan evento).
 * {@code phone}: número visible del cliente cuando el chat es @lid (de {@code key.remoteJidAlt}), o null.
 */
public record InboundMessageEvent(UUID tenant, String chat, String messageId, String body, String phone) {
    public InboundMessageEvent(UUID tenant, String chat, String messageId, String body) {
        this(tenant, chat, messageId, body, null);
    }
}
