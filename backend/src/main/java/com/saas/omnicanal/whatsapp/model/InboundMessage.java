package com.saas.omnicanal.whatsapp.model;

import java.time.OffsetDateTime;

public record InboundMessage(String chat, String id, String text, OffsetDateTime receivedAt) {
}
