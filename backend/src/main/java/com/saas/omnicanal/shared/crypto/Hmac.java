package com.saas.omnicanal.shared.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Utilidades HMAC-SHA256 con comparación en tiempo constante, para firmas de webhooks. */
public final class Hmac {
    private Hmac() {}

    public static byte[] sha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static String sha256Hex(String key, byte[] data) {
        return HexFormat.of().formatHex(sha256(key.getBytes(StandardCharsets.UTF_8), data));
    }

    public static boolean equalsConstantTime(String expected, String supplied) {
        if (expected == null || supplied == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
}
