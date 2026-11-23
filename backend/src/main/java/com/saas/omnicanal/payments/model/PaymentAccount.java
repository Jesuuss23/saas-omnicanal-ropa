package com.saas.omnicanal.payments.model;

import java.time.OffsetDateTime;

/** Vista segura de la cuenta de Mercado Pago vinculada por la tienda (sin tokens). */
public record PaymentAccount(boolean available, boolean linked, Long providerUserId, boolean liveMode, String status,
                             String lastError, OffsetDateTime linkedAt, OffsetDateTime tokenExpiresAt) {
}
