package com.saas.omnicanal.onboarding.service;

import com.saas.omnicanal.notifications.service.EmailService;
import com.saas.omnicanal.notifications.service.EmailTemplates;
import com.saas.omnicanal.onboarding.model.SignupInput;
import com.saas.omnicanal.onboarding.repository.SignupRepository;
import com.saas.omnicanal.shared.crypto.Tokens;
import com.saas.omnicanal.subscription.service.SubscriptionService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Prueba gratis: solicitud con enlace mágico por correo y creación de la tienda al confirmarlo. */
@Service
public class SignupService {
    private static final Duration TTL = Duration.ofHours(48);
    private static final int MAX_PER_HOUR = 3;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d 'de' MMMM", Locale.forLanguageTag("es-PE"))
            .withZone(ZoneId.of("America/Lima"));
    private final SignupRepository signups;
    private final SubscriptionService subscriptions;
    private final PasswordEncoder passwords;
    private final EmailService email;
    private final EmailTemplates templates;

    public SignupService(SignupRepository signups, SubscriptionService subscriptions, PasswordEncoder passwords,
                         EmailService email, EmailTemplates templates) {
        this.signups = signups;
        this.subscriptions = subscriptions;
        this.passwords = passwords;
        this.email = email;
        this.templates = templates;
    }

    public record Preview(String storeName, String email, String ownerName) {}
    public record Created(UUID store, String email) {}

    /** Siempre responde igual (no revela si el correo existe). Los bots que llenan el campo trampa se ignoran. */
    @Transactional
    public void request(SignupInput input) {
        if (input.website() != null && !input.website().isBlank()) return;
        String to = input.email().strip().toLowerCase(Locale.ROOT);
        if (signups.recentRequests(to, Duration.ofHours(1)) >= MAX_PER_HOUR) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Ya enviamos varios enlaces a este correo. Revisa tu bandeja (y spam) o intenta en una hora.");
        }
        String token = Tokens.random();
        String phone = input.phone() == null || input.phone().isBlank() ? null : input.phone().replace(" ", "");
        signups.create(to, input.storeName().strip(), input.ownerName().strip(), phone,
                input.plan() == null || input.plan().isBlank() ? null : input.plan(), Tokens.sha256(token), TTL);
        email.send(templates.signupConfirmation(to, input.ownerName().strip(), input.storeName().strip(),
                templates.url("/prueba-gratis/confirmar#" + token)));
    }

    public Preview preview(String token) {
        var pending = open(token, false);
        return new Preview(pending.storeName(), pending.email(), pending.ownerName());
    }

    @Transactional
    public Created confirm(String token, String password) {
        if (password == null || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La contraseña debe tener al menos 12 caracteres.");
        }
        var pending = open(token, true);
        UUID store = UUID.randomUUID();
        signups.createStore(store, pending.storeName(), pending.email(), passwords.encode(password), pending.ownerName(), pending.phone());
        subscriptions.startTrial(store);
        signups.markConfirmed(pending.id(), store);
        var overview = subscriptions.overview(store);
        String ends = overview.trialEndsAt() == null ? "fin de tu prueba" : DATE.format(overview.trialEndsAt());
        email.send(templates.welcome(pending.email(), pending.ownerName(), pending.storeName(),
                templates.url("/propietario/" + store), ends));
        return new Created(store, pending.email());
    }

    private SignupRepository.Pending open(String token, boolean lock) {
        if (!Tokens.wellFormed(token)) throw invalid();
        return signups.findOpen(Tokens.sha256(token), lock).orElseThrow(SignupService::invalid);
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.GONE, "El enlace venció o ya se usó. Solicita uno nuevo.");
    }
}
