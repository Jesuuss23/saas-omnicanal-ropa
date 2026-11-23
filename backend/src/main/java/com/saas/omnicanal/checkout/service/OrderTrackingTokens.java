package com.saas.omnicanal.checkout.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Token del enlace privado de seguimiento de un pedido de invitado (derivado de la reserva, que solo conoce el comprador). */
public final class OrderTrackingTokens {
    private OrderTrackingTokens() {}

    public static String token(UUID store, UUID order, UUID reservation) {
        String input = "order-tracking-v1/" + store + "/" + order + "/" + reservation;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    public static boolean matches(UUID store, UUID order, UUID reservation, String supplied) {
        if (supplied == null || supplied.length() != 64) return false;
        return MessageDigest.isEqual(token(store, order, reservation).getBytes(StandardCharsets.US_ASCII),
                supplied.getBytes(StandardCharsets.US_ASCII));
    }
}
