package com.saas.omnicanal.payments.model;

import java.time.OffsetDateTime;

/**
 * Vista segura de la cuenta Culqi de la tienda: nunca incluye la llave secreta, solo sus últimos 4 caracteres.
 * {@code webhookUrl} es la URL que el dueño pega en el panel de Culqi (null si falta PUBLIC_API_BASE_URL).
 */
public record PaymentAccount(boolean available, boolean linked, String provider, String publicKey, String secretKeyHint,
                             boolean liveMode, String status, String lastError, OffsetDateTime linkedAt, String webhookUrl) {
}
