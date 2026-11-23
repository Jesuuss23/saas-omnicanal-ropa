package com.saas.omnicanal.auth.service;

import com.saas.omnicanal.auth.model.EnrollmentRecord;
import com.saas.omnicanal.auth.model.MfaSetup;
import com.saas.omnicanal.auth.model.MfaStatus;
import com.saas.omnicanal.auth.model.MfaSubject;
import com.saas.omnicanal.auth.repository.MfaRepository;
import com.saas.omnicanal.shared.crypto.SecretCipher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Enrolamiento y verificación del segundo factor (TOTP) con códigos de recuperación.
 * El secreto se guarda cifrado con {@link SecretCipher}; los códigos de recuperación, solo como hash.
 */
@Service
public class MfaService {
    public static final String ISSUER = "Omnicanal";
    public static final int RECOVERY_CODES = 10;
    private static final Duration SETUP_TTL = Duration.ofMinutes(15);
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final MfaRepository repository;
    private final SecretCipher cipher;
    private final SecureRandom random = new SecureRandom();

    public MfaService(MfaRepository repository, SecretCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    public MfaStatus status(MfaSubject type, String subject) {
        return repository.find(type, subject).map(r -> new MfaStatus(r.secret() != null, r.confirmedAt(),
                        r.secret() == null ? 0 : repository.recoveryCodesLeft(type, subject), pendingValid(r)))
                .orElse(MfaStatus.none());
    }

    public boolean enabled(MfaSubject type, String subject) {
        return repository.find(type, subject).map(r -> r.secret() != null).orElse(false);
    }

    /** Genera un secreto pendiente; el activo (si existe) sigue valiendo hasta confirmar el nuevo. */
    @Transactional
    public MfaSetup startSetup(MfaSubject type, String subject, String accountLabel) {
        requireCipher();
        String secret = Totp.newSecret();
        repository.savePending(type, subject, cipher.encrypt(context(type, subject), secret));
        String uri = Totp.otpauthUri(ISSUER, accountLabel, secret);
        return new MfaSetup(secret, uri, Totp.qrDataUrl(uri));
    }

    /** Confirma el secreto pendiente con un código de la app y devuelve los códigos de recuperación nuevos. */
    @Transactional
    public List<String> confirmSetup(MfaSubject type, String subject, String code) {
        requireCipher();
        EnrollmentRecord record = repository.find(type, subject).filter(this::pendingValid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "La configuración venció. Vuelve a generar el código QR."));
        String secret = cipher.decrypt(context(type, subject), record.pendingSecret());
        long step = Totp.matchingStep(secret, normalize(code), -1)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El código no coincide. Revisa la hora de tu celular y usa el código vigente."));
        if (!repository.promotePending(type, subject, record.pendingSecret(), step)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La configuración cambió en otra sesión.");
        }
        return regenerateRecoveryCodes(type, subject);
    }

    /** Verifica un código de 6 dígitos o un código de recuperación (que queda usado). */
    @Transactional
    public boolean verify(MfaSubject type, String subject, String code) {
        if (code == null || code.isBlank() || !cipher.configured()) return false;
        EnrollmentRecord record = repository.find(type, subject).orElse(null);
        if (record == null || record.secret() == null) return false;
        String normalized = normalize(code);
        if (normalized.matches("[0-9]{6}")) {
            String secret = cipher.decrypt(context(type, subject), record.secret());
            var step = Totp.matchingStep(secret, normalized, record.lastStep());
            return step.isPresent() && repository.useStep(type, subject, step.getAsLong());
        }
        return normalized.matches("[A-Z0-9]{8}") && repository.useRecoveryCode(type, subject, hash(normalized));
    }

    @Transactional
    public List<String> regenerateRecoveryCodes(MfaSubject type, String subject) {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            StringBuilder code = new StringBuilder();
            for (int j = 0; j < 8; j++) code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            codes.add(code.substring(0, 4) + "-" + code.substring(4));
        }
        repository.replaceRecoveryCodes(type, subject, codes.stream().map(c -> hash(normalize(c))).toList());
        return codes;
    }

    @Transactional
    public void disable(MfaSubject type, String subject) {
        repository.delete(type, subject);
    }

    private boolean pendingValid(EnrollmentRecord record) {
        return record.pendingSecret() != null && record.pendingCreatedAt() != null
                && record.pendingCreatedAt().isAfter(Instant.now().minus(SETUP_TTL));
    }

    private void requireCipher() {
        if (!cipher.configured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Falta configurar SECRETS_ENCRYPTION_KEY en el servidor para activar la verificación en dos pasos.");
        }
    }

    private static String context(MfaSubject type, String subject) {
        return "mfa:" + type.name() + ":" + subject;
    }

    /** Quita espacios y guiones y pasa a mayúsculas: "abcd-efgh" y "ABCD EFGH" valen igual. */
    static String normalize(String code) {
        return code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    private static String hash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
