package com.saas.omnicanal.auth.model;

import org.springframework.security.core.AuthenticationException;

/** La contraseña es correcta pero falta el código de la app autenticadora. */
public class MfaRequiredException extends AuthenticationException {
    public MfaRequiredException() {
        super("Falta el código de verificación");
    }
}
