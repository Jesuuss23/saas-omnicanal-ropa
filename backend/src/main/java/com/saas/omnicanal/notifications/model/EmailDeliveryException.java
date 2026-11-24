package com.saas.omnicanal.notifications.model;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resend no aceptó el correo (clave inválida, remitente no verificado, destinatario no permitido, red caída...).
 * Responde 502 para que el frontend muestre el error en vez de una pantalla de éxito.
 */
public class EmailDeliveryException extends ResponseStatusException {
    public EmailDeliveryException(String detail, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, "No pudimos enviar el correo. Intenta de nuevo en unos minutos.", cause);
        if (detail != null) setDetail(detail);
    }
}
