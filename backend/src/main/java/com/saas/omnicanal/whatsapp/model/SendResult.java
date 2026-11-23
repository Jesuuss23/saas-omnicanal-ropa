package com.saas.omnicanal.whatsapp.model;

/** Resultado de un envío: ACCEPTED (con id del proveedor), REJECTED (error definitivo) o UNKNOWN (no confirmado). */
public record SendResult(String status, String providerMessageId, String error) {
    public static SendResult accepted(String id) { return new SendResult("ACCEPTED", id, null); }
    public static SendResult rejected(String error) { return new SendResult("REJECTED", null, error); }
    public static SendResult unknown(String error) { return new SendResult("UNKNOWN", null, error); }
}
