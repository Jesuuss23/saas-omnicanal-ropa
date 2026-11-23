package com.saas.omnicanal.whatsapp.model;

import java.time.OffsetDateTime;

public record ManualReply(String text, String status, OffsetDateTime createdAt) {
}
