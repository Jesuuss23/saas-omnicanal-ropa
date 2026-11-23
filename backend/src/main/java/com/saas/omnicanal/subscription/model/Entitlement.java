package com.saas.omnicanal.subscription.model;

/** Capacidad efectiva. limit == null significa sin límite. */
public record Entitlement(String key, boolean enabled, Long limit) {
    public boolean allows(long usageAfter) {
        return enabled && (limit == null || usageAfter <= limit);
    }
}
