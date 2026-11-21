package com.saas.omnicanal.platformadmin;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Base32;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AdminLogin {
    private final JdbcTemplate db;
    private final String email;
    private final String passwordHash;
    private final String secret;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    AdminLogin(JdbcTemplate db, @Value("${SUPERADMIN_EMAIL:}") String email,
               @Value("${SUPERADMIN_PASSWORD_HASH:}") String passwordHash,
               @Value("${SUPERADMIN_TOTP_SECRET:}") String secret) {
        this.db = db;
        this.email = email.strip().toLowerCase(Locale.ROOT);
        this.passwordHash = passwordHash;
        this.secret = secret;
    }

    @Transactional(noRollbackFor = BadCredentialsException.class)
    public Authentication authenticate(Authentication request) {
        if (email.isEmpty() || passwordHash.isEmpty() || !secret.matches("[A-Z2-7]{32}")
                || !email.equalsIgnoreCase(request.getName())) {
            throw denied();
        }
        db.update("INSERT INTO platform_admin_login(email) VALUES (?) ON CONFLICT DO NOTHING", email);
        int attempts = db.update("""
                UPDATE platform_admin_login SET
                    failures = CASE WHEN window_started <= now() - interval '5 minutes' THEN 1 ELSE failures + 1 END,
                    window_started = CASE WHEN window_started <= now() - interval '5 minutes' THEN now() ELSE window_started END
                WHERE email = ? AND (failures < 5 OR window_started <= now() - interval '5 minutes')
                """, email);
        if (attempts == 0 || !encoder.matches((String) request.getCredentials(), passwordHash)) {
            throw denied();
        }
        String code = (String) request.getDetails();
        if (code == null || !code.matches("[0-9]{6}")) {
            throw denied();
        }
        long now = Instant.now().getEpochSecond() / 30;
        for (long step = now - 1; step <= now + 1; step++) {
            if (code.equals(codeAt(secret, step)) && db.update("""
                    UPDATE platform_admin_login SET last_step = ?, failures = 0
                    WHERE email = ? AND last_step < ?
                    """, step, email, step) == 1) {
                return UsernamePasswordAuthenticationToken.authenticated(email, null,
                        AuthorityUtils.createAuthorityList("ROLE_PLATFORM_ADMIN"));
            }
        }
        throw denied();
    }

    static String codeAt(String secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(new Base32().decode(secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 15;
            int value = ByteBuffer.wrap(hash, offset, 4).getInt() & Integer.MAX_VALUE;
            return String.format(Locale.ROOT, "%06d", value % 1000000);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static BadCredentialsException denied() {
        return new BadCredentialsException("Acceso denegado");
    }
}
