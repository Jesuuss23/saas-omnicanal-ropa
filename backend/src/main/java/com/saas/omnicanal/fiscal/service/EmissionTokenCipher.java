package com.saas.omnicanal.fiscal.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class EmissionTokenCipher {
    private final Environment config;
    private final SecureRandom random = new SecureRandom();

    EmissionTokenCipher(Environment config) {
        this.config = config;
    }

    public SecretKeySpec keyIfConfigured() {
        String encoded = config.getProperty("APISPERU_EMISSION_KEY", "");
        if (encoded.isBlank()) return null;
        try {
            byte[] key = Base64.getDecoder().decode(encoded);
            if (key.length == 32) return new SecretKeySpec(key, "AES");
        } catch (IllegalArgumentException ignored) {
            // Report configuration failure without exposing the key.
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }

    public String decrypt(UUID store, long companyId, String ruc, byte[] envelope) {
        var key = keyIfConfigured();
        if (key == null || envelope == null || envelope.length < 29)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, envelope, 0, 12));
            cipher.updateAAD((store + "/" + companyId + "/" + ruc).getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(envelope, 12, envelope.length-12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    public byte[] encrypt(SecretKeySpec key, UUID store, long companyId, String ruc, String token) {
        byte[] nonce = new byte[12];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD((store + "/" + companyId + "/" + ruc).getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("No se pudo cifrar el token de emisión.", ex);
        }
    }
}
