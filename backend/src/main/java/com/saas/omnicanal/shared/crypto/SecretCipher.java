package com.saas.omnicanal.shared.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cifrado AES-256-GCM para secretos de integraciones (tokens de Meta, Mercado Pago, Culqi...).
 * La clave se lee de {@code SECRETS_ENCRYPTION_KEY} (32 bytes en Base64) y nunca se guarda en la base.
 * El contexto (AAD) ata cada ciphertext a su tienda y propósito: no se puede copiar entre filas.
 */
@Component
public class SecretCipher {
    private static final int NONCE = 12;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${SECRETS_ENCRYPTION_KEY:}") String encoded) {
        this.key = parse(encoded);
        var log = org.slf4j.LoggerFactory.getLogger(SecretCipher.class);
        if (key != null) log.info("Cifrado: SECRETS_ENCRYPTION_KEY cargada");
        else if (encoded == null || encoded.isBlank())
            log.warn("Cifrado: falta SECRETS_ENCRYPTION_KEY. No funcionarán la verificación en dos pasos, WhatsApp Cloud API, "
                    + "Mercado Pago ni la facturación. Genera una con: openssl rand -base64 32");
        else
            log.error("Cifrado: SECRETS_ENCRYPTION_KEY no es válida (debe ser Base64 de 32 bytes). Genera una con: openssl rand -base64 32");
    }

    private static SecretKeySpec parse(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            byte[] raw = Base64.getDecoder().decode(encoded.strip());
            return raw.length == 32 ? new SecretKeySpec(raw, "AES") : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    public boolean configured() {
        return key != null;
    }

    public byte[] encrypt(String context, String plaintext) {
        SecretKeySpec k = requireKey();
        byte[] nonce = new byte[NONCE];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, k, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] out = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(NONCE + out.length).put(nonce).put(out).array();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("No se pudo cifrar el secreto.", ex);
        }
    }

    public String decrypt(String context, byte[] envelope) {
        SecretKeySpec k = requireKey();
        if (envelope == null || envelope.length <= NONCE + 16)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, envelope, 0, NONCE));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(envelope, NONCE, envelope.length - NONCE), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    /** Valor estable derivado de la clave maestra (p. ej. verify token de webhooks), url-safe. */
    public String derive(String label) {
        SecretKeySpec k = requireKey();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(k.getEncoded(), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(label.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private SecretKeySpec requireKey() {
        if (key == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Falta configurar SECRETS_ENCRYPTION_KEY en el servidor");
        return key;
    }
}
