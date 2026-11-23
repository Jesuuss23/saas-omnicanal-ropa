package com.saas.omnicanal.subscription.model;

/** Estados de la suscripción de la tienda al SaaS (sección 13 del documento). */
public enum SubscriptionStatus {
    TRIAL, TRIAL_EXPIRED, PENDING_PAYMENT, ACTIVE, GRACE, SUSPENDED, CANCELLED;

    /** Estados en los que rigen los entitlements del plan contratado. */
    public boolean grantsPlan() {
        return this == TRIAL || this == ACTIVE || this == GRACE;
    }
}
