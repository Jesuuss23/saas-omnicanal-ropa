package com.saas.omnicanal.whatsapp.model;

import java.time.OffsetDateTime;

/** Plantilla de mensaje sincronizada desde la cuenta de WhatsApp Business (WABA). */
public record WhatsAppTemplate(String id, String name, String language, String category, String status,
                               String body, int bodyParameters, String componentsJson, OffsetDateTime syncedAt) {
}
