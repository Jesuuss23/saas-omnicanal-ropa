package com.saas.omnicanal.marketing.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class MarketingModels {
    private MarketingModels() {}

    public enum Channel { EMAIL, WHATSAPP }

    /**
     * Segmento de contactos con consentimiento para el canal elegido.
     * {@code buyers}: ANY (todos), BUYERS (compraron al menos una vez pagado) o NON_BUYERS (nunca pagaron).
     */
    public record Segment(String buyers, Integer lastOrderWithinDays, Integer noOrderForDays, BigDecimal minSpent) {
        public static Segment all() {
            return new Segment("ANY", null, null, null);
        }
    }

    public record Stats(long total, long pending, long sent, long failed, long skipped) {}

    public record Campaign(UUID id, String name, Channel channel, Segment segment, String subject, String body,
                           String templateName, String templateLang, String status, String createdBy, Instant createdAt,
                           Instant startedAt, Instant finishedAt, long revision, Stats stats) {}

    public record Recipient(UUID tenant, UUID campaign, UUID contact, String destination, String name, String unsubscribeToken) {}

    public record AbandonedSettings(boolean enabled, int delayMinutes, String templateName, String templateLang, long revision) {}

    public record AbandonedCart(UUID tenant, UUID reservation, String phone, String name) {}

    public record Preferences(String storeName, String email, boolean emailOptIn, boolean whatsappOptIn) {}
}
