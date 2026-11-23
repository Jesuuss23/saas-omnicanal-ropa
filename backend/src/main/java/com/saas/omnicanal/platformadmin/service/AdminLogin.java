package com.saas.omnicanal.platformadmin.service;

import com.saas.omnicanal.auth.model.MfaRequiredException;
import com.saas.omnicanal.auth.model.MfaSubject;
import com.saas.omnicanal.auth.service.MfaService;
import com.saas.omnicanal.auth.service.Totp;
import java.util.Locale;
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
public class AdminLogin {
    private final JdbcTemplate db;
    private final String email;
    private final String passwordHash;
    private final String secret;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final MfaService mfa;

    public static final String FULL_ROLE = "ROLE_PLATFORM_ADMIN";
    /** Sesión a medias: contraseña correcta pero todavía sin app autenticadora enrolada. */
    public static final String SETUP_ROLE = "ROLE_PLATFORM_ADMIN_SETUP";

    AdminLogin(JdbcTemplate db, MfaService mfa, @Value("${SUPERADMIN_EMAIL:}") String email,
               @Value("${SUPERADMIN_PASSWORD_HASH:}") String passwordHash,
               @Value("${SUPERADMIN_TOTP_SECRET:}") String secret) {
        this.db = db;
        this.mfa = mfa;
        this.email = email.strip().toLowerCase(Locale.ROOT);
        this.passwordHash = passwordHash;
        this.secret = secret;
    }

    @Transactional(noRollbackFor = {BadCredentialsException.class, MfaRequiredException.class})
    public Authentication authenticate(Authentication request) {
        if (email.isEmpty() || passwordHash.isEmpty() || !email.equalsIgnoreCase(request.getName())) {
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
        if (mfa.enabled(MfaSubject.SUPERADMIN, email)) {
            // Segundo factor enrolado con QR: código de la app o código de recuperación.
            if (code == null || code.isBlank()) throw new MfaRequiredException();
            if (mfa.verify(MfaSubject.SUPERADMIN, email, code)) {
                db.update("UPDATE platform_admin_login SET failures = 0 WHERE email = ?", email);
                return admin(FULL_ROLE);
            }
            throw denied();
        }
        if (secret.matches("[A-Z2-7]{32}")) {
            // Secreto heredado de la variable SUPERADMIN_TOTP_SECRET.
            if (code == null || code.isBlank()) throw new MfaRequiredException();
            if (code == null || !code.matches("[0-9]{6}")) throw denied();
            long now = Totp.currentStep();
            for (long step = now - 1; step <= now + 1; step++) {
                if (code.equals(codeAt(secret, step)) && db.update("""
                        UPDATE platform_admin_login SET last_step = ?, failures = 0
                        WHERE email = ? AND last_step < ?
                        """, step, email, step) == 1) {
                    return admin(FULL_ROLE);
                }
            }
            throw denied();
        }
        // Sin segundo factor todavía: la sesión solo permite enrolar la app autenticadora.
        db.update("UPDATE platform_admin_login SET failures = 0 WHERE email = ?", email);
        return admin(SETUP_ROLE);
    }

    /** Hay un secreto heredado por variable de entorno (sin enrolamiento en base de datos). */
    public boolean usesEnvironmentSecret() {
        return secret.matches("[A-Z2-7]{32}");
    }

    public String email() {
        return email;
    }

    private Authentication admin(String role) {
        return UsernamePasswordAuthenticationToken.authenticated(email, null, AuthorityUtils.createAuthorityList(role));
    }

    public static String codeAt(String secret, long step) {
        return Totp.codeAt(secret, step);
    }

    private static BadCredentialsException denied() {
        return new BadCredentialsException("Acceso denegado");
    }
}
