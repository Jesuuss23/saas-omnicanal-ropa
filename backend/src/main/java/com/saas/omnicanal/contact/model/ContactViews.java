package com.saas.omnicanal.contact.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Vistas del panel y de la cuenta del comprador. */
public final class ContactViews {
    private ContactViews() {}

    public record Row(UUID id, String fullName, String phone, String email, boolean google, boolean whatsapp,
                      long orders, BigDecimal spent, Instant lastOrderAt, Instant updatedAt) {}

    public record Order(UUID id, Instant createdAt, String status, BigDecimal total, String deliveryMethod, String trackingPath) {}

    public record Merge(UUID id, UUID survivorId, UUID mergedId, String mergedName, int movedOrders, String mergedBy,
                        Instant mergedAt, Instant revertedAt) {}

    public record Detail(Contact contact, boolean whatsapp, List<Order> orders, List<Merge> merges) {}

    /** Dos contactos que probablemente son la misma persona, con el motivo legible. */
    public record Duplicate(Row first, Row second, String reason) {}

    public record Profile(UUID id, String fullName, String email, String phone, List<Order> orders) {}
}
