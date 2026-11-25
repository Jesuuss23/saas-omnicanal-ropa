package com.saas.omnicanal.payments.model;

/** Estado del pago de un pedido web, normalizado desde el cargo de Culqi. */
public enum OrderPaymentStatus {
    PENDING, IN_PROCESS, APPROVED, REJECTED, CANCELLED, REFUNDED, CHARGED_BACK;

    /** Culqi marca el cargo aprobado con {@code outcome.type = "venta_exitosa"}. */
    public static OrderPaymentStatus fromCulqi(String outcomeType) {
        if ("venta_exitosa".equals(outcomeType)) return APPROVED;
        return outcomeType == null || outcomeType.isBlank() ? PENDING : REJECTED;
    }
}
