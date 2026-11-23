package com.saas.omnicanal.onboarding.service;

import com.saas.omnicanal.notifications.service.EmailService;
import com.saas.omnicanal.notifications.service.EmailTemplates;
import com.saas.omnicanal.shared.crypto.Tokens;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Recuperación de contraseña de propietarios con enlace por correo (vence en 1 hora). */
@Service
public class PasswordResetService {
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;
    private final EmailService email;
    private final EmailTemplates templates;

    public PasswordResetService(JdbcTemplate db, PasswordEncoder passwords, EmailService email, EmailTemplates templates) {
        this.db = db;
        this.passwords = passwords;
        this.email = email;
        this.templates = templates;
    }

    private record Owner(UUID store, String name) {}

    /** No revela si el correo existe. Como mucho un enlace por minuto por tienda. */
    @Transactional
    public void request(String rawEmail) {
        String to = rawEmail.strip().toLowerCase(Locale.ROOT);
        var owners = db.query("""
                SELECT t.id, t.name FROM tenant_owner o JOIN tenant t ON t.id = o.tenant_id
                WHERE lower(o.email) = ? AND o.password_hash IS NOT NULL ORDER BY t.name
                """, (rs, row) -> new Owner(rs.getObject(1, UUID.class), rs.getString(2)), to);
        var links = new ArrayList<EmailTemplates.StoreLink>();
        for (var owner : owners) {
            String token = Tokens.random();
            int saved = db.update("""
                    INSERT INTO owner_password_reset(tenant_id, token_hash, expires_at) VALUES (?, ?, now() + interval '1 hour')
                    ON CONFLICT (tenant_id) DO UPDATE SET token_hash = EXCLUDED.token_hash, expires_at = EXCLUDED.expires_at,
                        created_at = now()
                    WHERE owner_password_reset.created_at < now() - interval '1 minute'
                    """, owner.store(), Tokens.sha256(token));
            if (saved == 1) {
                links.add(new EmailTemplates.StoreLink(owner.name(),
                        templates.url("/recuperar-acceso/confirmar?store=" + owner.store() + "#" + token)));
            }
        }
        if (!links.isEmpty()) email.send(templates.passwordReset(to, links));
    }

    @Transactional
    public void confirm(UUID store, String token, String password) {
        if (password == null || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La contraseña debe tener al menos 12 caracteres.");
        }
        if (!Tokens.wellFormed(token) || db.update("""
                DELETE FROM owner_password_reset WHERE tenant_id = ? AND token_hash = ? AND expires_at > now()
                """, store, Tokens.sha256(token)) != 1) {
            throw new ResponseStatusException(HttpStatus.GONE, "El enlace venció o ya se usó. Solicita uno nuevo.");
        }
        db.update("UPDATE tenant_owner SET password_hash = ? WHERE tenant_id = ?", passwords.encode(password), store);
        db.update("""
                DELETE FROM owner_login_attempt WHERE email = (SELECT lower(email) FROM tenant_owner WHERE tenant_id = ?)
                """, store);
    }
}
