package com.saas.omnicanal.team.model;

/**
 * Roles dentro de una tienda. OWNER es el propietario (tenant_owner); MANAGER y SELLER son miembros invitados.
 * Cada rol se traduce en una autoridad de Spring Security (ROLE_OWNER, ROLE_MANAGER, ROLE_SELLER).
 */
public enum StoreRole {
    OWNER, MANAGER, SELLER;

    public String authority() {
        return "ROLE_" + name();
    }
}
