package com.saas.omnicanal.shared.security;

import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** El propietario autenticado solo puede operar sobre su propia tienda (tenant). */
public final class OwnerAccess {
    private OwnerAccess() {}

    public static void requireOwner(UUID store, Principal principal) {
        if (principal == null || !store.toString().equals(principal.getName()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
}
