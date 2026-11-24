package com.saas.omnicanal.whatsapp.model;

import java.util.UUID;

/** Se publica una sola vez por mensaje entrante nuevo (los reenvíos del proveedor no generan evento). */
public record InboundMessageEvent(UUID tenant, String chat, String messageId, String body) {}
