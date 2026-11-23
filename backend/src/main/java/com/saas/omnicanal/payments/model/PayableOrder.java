package com.saas.omnicanal.payments.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Pedido web listo para cobrar: monto final (mercadería + delivery) y vencimiento de la reserva. */
public record PayableOrder(UUID tenantId, UUID orderId, UUID reservationId, String status, BigDecimal amount,
                           boolean amountKnown, boolean reservationActive, OffsetDateTime reservationExpiresAt,
                           String storeName) {
}
