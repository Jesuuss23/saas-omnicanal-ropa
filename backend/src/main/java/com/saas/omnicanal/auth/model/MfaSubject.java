package com.saas.omnicanal.auth.model;

/** Quién usa el segundo factor: el superadmin (por correo) o el propietario de una tienda (por id de tienda). */
public enum MfaSubject {
    SUPERADMIN,
    OWNER
}
