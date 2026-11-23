package com.saas.omnicanal.owner.service;

import com.saas.omnicanal.auth.model.MfaRequiredException;
import com.saas.omnicanal.auth.model.MfaSubject;
import com.saas.omnicanal.auth.service.MfaService;
import com.saas.omnicanal.owner.model.ChooseStoreException;
import com.saas.omnicanal.owner.model.ChooseStoreException.StoreOption;
import com.saas.omnicanal.owner.model.OwnerLoginDetails;
import com.saas.omnicanal.team.model.StoreRole;
import com.saas.omnicanal.team.model.StoreSession;
import com.saas.omnicanal.team.repository.MemberRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ingreso del propietario con correo y contraseña (o, por compatibilidad, con el id de la tienda),
 * más el segundo factor si lo activó. También ingresan los miembros del equipo (encargados y vendedores).
 * El principal de la sesión es el id de la tienda; quién entró y con qué rol va en {@link StoreSession}.
 */
@Service
public class OwnerLogin {
    public static final String ROLE = "ROLE_OWNER";
    private final JdbcTemplate db;
    private final PasswordEncoder passwords;
    private final MfaService mfa;
    private final MemberRepository members;

    public OwnerLogin(JdbcTemplate db, PasswordEncoder passwords, MfaService mfa, MemberRepository members) {
        this.db = db;
        this.passwords = passwords;
        this.mfa = mfa;
        this.members = members;
    }

    private record Candidate(UUID id, String name, String hash, UUID member, String email, StoreRole role) {}

    @Transactional(noRollbackFor = {BadCredentialsException.class, MfaRequiredException.class, ChooseStoreException.class})
    public Authentication authenticate(Authentication request) {
        String username = request.getName() == null ? "" : request.getName().strip().toLowerCase(Locale.ROOT);
        String password = request.getCredentials() == null ? "" : request.getCredentials().toString();
        var details = request.getDetails() instanceof OwnerLoginDetails d ? d : new OwnerLoginDetails(null, null);
        if (username.isEmpty() || username.length() > 254 || password.isEmpty()) throw denied();
        if (!countAttempt(username)) throw denied();

        UUID chosen = parse(details.store());
        UUID byId = parse(username);
        List<Candidate> candidates = new ArrayList<>(db.query("""
                SELECT t.id, t.name, o.password_hash, o.email FROM tenant_owner o JOIN tenant t ON t.id = o.tenant_id
                WHERE o.password_hash IS NOT NULL AND (CASE WHEN ?::uuid IS NULL THEN lower(o.email) = ? ELSE o.tenant_id = ?::uuid END)
                  AND (?::uuid IS NULL OR o.tenant_id = ?::uuid)
                ORDER BY t.name, t.id
                """, (rs, row) -> new Candidate(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), null,
                rs.getString(4), StoreRole.OWNER), byId, username, byId, chosen, chosen));
        if (byId == null) {
            members.loginCandidates(username, chosen).forEach(m -> candidates.add(
                    new Candidate(m.tenant(), m.storeName(), m.hash(), m.memberId(), m.email(), m.role())));
        }
        List<Candidate> matches = candidates.stream().filter(c -> passwords.matches(password, c.hash())).toList();
        if (matches.isEmpty()) throw denied();
        if (matches.size() > 1) {
            throw new ChooseStoreException(matches.stream().map(c -> new StoreOption(c.id(), c.name())).toList());
        }
        Candidate match = matches.get(0);
        UUID store = match.id();
        if (match.member() == null && mfa.enabled(MfaSubject.OWNER, store.toString())) {
            if (details.code() == null || details.code().isBlank()) throw new MfaRequiredException();
            if (!mfa.verify(MfaSubject.OWNER, store.toString(), details.code())) throw denied();
        }
        db.update("UPDATE owner_login_attempt SET failures = 0 WHERE email = ?", username);
        if (match.member() != null) members.touchLogin(match.member());
        var token = UsernamePasswordAuthenticationToken.authenticated(store.toString(), null,
                AuthorityUtils.createAuthorityList(match.role().authority()));
        token.setDetails(new StoreSession(match.member(), match.email(), match.role()));
        return token;
    }

    /** Cuenta el intento; devuelve false si ya hubo 5 fallos en los últimos 5 minutos. */
    private boolean countAttempt(String key) {
        db.update("INSERT INTO owner_login_attempt(email) VALUES (?) ON CONFLICT DO NOTHING", key);
        return db.update("""
                UPDATE owner_login_attempt SET
                    failures = CASE WHEN window_started <= now() - interval '5 minutes' THEN 1 ELSE failures + 1 END,
                    window_started = CASE WHEN window_started <= now() - interval '5 minutes' THEN now() ELSE window_started END
                WHERE email = ? AND (failures < 5 OR window_started <= now() - interval '5 minutes')
                """, key) == 1;
    }

    private static UUID parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static BadCredentialsException denied() {
        return new BadCredentialsException("Acceso denegado");
    }
}
