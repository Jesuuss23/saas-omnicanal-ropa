package com.saas.omnicanal.subscription.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Subscription(UUID tenantId, String planCode, SubscriptionStatus status, OffsetDateTime trialEndsAt,
                           OffsetDateTime currentPeriodEnd, OffsetDateTime graceEndsAt, String pendingPlanCode,
                           String culqiCustomerId, String culqiCardId, String culqiSubscriptionId, long revision) {
}
