package com.saas.omnicanal.auth.model;

import java.time.Instant;

public record EnrollmentRecord(byte[] secret, byte[] pendingSecret, Instant pendingCreatedAt,
                               Instant confirmedAt, long lastStep) {}
