package com.saas.omnicanal.payments.model;

import java.util.Locale;

/** Estados de pago del pedido, normalizados desde los estados de Mercado Pago. */
public enum OrderPaymentStatus {
    PENDING, IN_PROCESS, APPROVED, REJECTED, CANCELLED, REFUNDED, CHARGED_BACK;

    public static OrderPaymentStatus fromMercadoPago(String status) {
        return switch (status == null ? "" : status.toLowerCase(Locale.ROOT)) {
            case "approved" -> APPROVED;
            case "in_process", "authorized", "in_mediation" -> IN_PROCESS;
            case "rejected" -> REJECTED;
            case "cancelled" -> CANCELLED;
            case "refunded" -> REFUNDED;
            case "charged_back" -> CHARGED_BACK;
            default -> PENDING;
        };
    }
}
