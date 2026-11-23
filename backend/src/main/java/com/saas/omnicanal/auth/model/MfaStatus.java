package com.saas.omnicanal.auth.model;

import java.time.Instant;

/** Estado del segundo factor de una cuenta, sin secretos. */
public record MfaStatus(boolean enabled, Instant confirmedAt, int recoveryCodesLeft, boolean setupPending) {
    public static MfaStatus none() {
        return new MfaStatus(false, null, 0, false);
    }
}
