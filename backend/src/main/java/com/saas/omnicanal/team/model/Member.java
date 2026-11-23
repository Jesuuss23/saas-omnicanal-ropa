package com.saas.omnicanal.team.model;

import java.time.Instant;
import java.util.UUID;

public record Member(UUID id, String email, String fullName, StoreRole role, boolean active, boolean activated,
                     boolean invitationPending, Instant lastLoginAt, Instant createdAt) {}
