package com.saas.omnicanal.team.model;

import java.io.Serializable;
import java.util.UUID;

/** Quién entró al panel de la tienda. Va en los detalles de la autenticación; el principal sigue siendo el id de la tienda. */
public record StoreSession(UUID memberId, String email, StoreRole role) implements Serializable {}
