package com.saas.omnicanal.whatsapp.model;

import java.util.UUID;

/**
 * Se publica una sola vez por mensaje entrante nuevo (los reenvíos del proveedor no generan evento).
 * {@code phone}: número visible del cliente cuando el chat es @lid (de {@code key.remoteJidAlt}), o null.
 * {@code delivery}: las notas de voz y fotos llegan primero solo a la bandeja ({@code INBOX}); cuando terminan la
 * transcripción, la moderación y el análisis, el mismo mensaje se publica solo para la IA ({@code ASSISTANT}).
 */
public record InboundMessageEvent(UUID tenant, String chat, String messageId, String body, String phone, Delivery delivery) {
    public enum Delivery { ALL, INBOX, ASSISTANT }

    public InboundMessageEvent(UUID tenant, String chat, String messageId, String body, String phone) {
        this(tenant, chat, messageId, body, phone, Delivery.ALL);
    }

    public InboundMessageEvent(UUID tenant, String chat, String messageId, String body) {
        this(tenant, chat, messageId, body, null, Delivery.ALL);
    }

    public boolean forInbox() {
        return delivery != Delivery.ASSISTANT;
    }

    public boolean forAssistant() {
        return delivery != Delivery.INBOX;
    }
}
