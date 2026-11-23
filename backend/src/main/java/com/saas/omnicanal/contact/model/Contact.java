package com.saas.omnicanal.contact.model;

import java.time.Instant;
import java.util.UUID;

/** Persona que compró o escribió a la tienda. {@code mergedInto} apunta al contacto que la absorbió. */
public record Contact(UUID id, String fullName, String phone, String email, boolean google, UUID mergedInto,
                      Instant createdAt, Instant updatedAt) {}
