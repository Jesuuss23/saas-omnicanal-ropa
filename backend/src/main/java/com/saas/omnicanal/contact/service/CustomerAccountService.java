package com.saas.omnicanal.contact.service;

import com.saas.omnicanal.contact.model.Contact;
import com.saas.omnicanal.contact.model.ContactViews.Profile;
import com.saas.omnicanal.contact.model.GoogleIdentity;
import com.saas.omnicanal.contact.repository.ContactRepository;
import com.saas.omnicanal.contact.repository.CustomerSessionRepository;
import com.saas.omnicanal.shared.crypto.Tokens;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Cuenta del comprador en una tienda: ingreso con Google, sesión por cookie y sus pedidos. */
@Service
public class CustomerAccountService {
    private final ContactRepository contacts;
    private final CustomerSessionRepository sessions;
    private final ContactService contactService;
    private final GoogleTokenVerifier google;
    private final Duration sessionLength;

    public CustomerAccountService(ContactRepository contacts, CustomerSessionRepository sessions, ContactService contactService,
                                  GoogleTokenVerifier google, @Value("${customers.session-days:30}") int sessionDays) {
        this.contacts = contacts;
        this.sessions = sessions;
        this.contactService = contactService;
        this.google = google;
        this.sessionLength = Duration.ofDays(sessionDays);
    }

    public record Session(String token, Duration maxAge, Profile profile) {}

    public String googleClientId() {
        return google.clientId();
    }

    public Duration sessionLength() {
        return sessionLength;
    }

    /**
     * Ingreso con Google. Busca primero por la cuenta de Google y luego por el correo verificado; si no existe, crea el
     * contacto. Un correo verificado por Google sí prueba identidad, por eso se puede unir a un contacto existente.
     */
    @Transactional
    public Session signInWithGoogle(UUID tenant, String credential) {
        if (!google.configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Ingreso con Google no configurado.");
        if (!contacts.storeExists(tenant)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        GoogleIdentity id = google.verify(credential)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No pudimos verificar tu cuenta de Google."));
        Contact contact = contacts.activeByGoogle(tenant, id.subject())
                .or(() -> contacts.activeByEmail(tenant, id.email()).map(c -> {
                    contacts.fillMissing(tenant, c.id(), id.name(), null, null, id.subject());
                    return c;
                }))
                .or(() -> contacts.insert(tenant, id.name(), null, id.email(), id.subject()))
                .or(() -> contacts.activeByGoogle(tenant, id.subject()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT));
        String token = Tokens.random();
        sessions.insert(Tokens.sha256(token), tenant, contact.id(), Instant.now().plus(sessionLength));
        return new Session(token, sessionLength, profile(tenant, contact.id()));
    }

    /** Contacto de la sesión, si la cookie es válida para esta tienda. */
    public Optional<UUID> contactFor(UUID tenant, String token) {
        if (!Tokens.wellFormed(token)) return Optional.empty();
        return sessions.contact(Tokens.sha256(token), tenant);
    }

    @Transactional(readOnly = true)
    public Profile profile(UUID tenant, UUID contactId) {
        Contact c = contacts.find(tenant, contactId).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return new Profile(c.id(), c.fullName(), c.email(), c.phone(), contactService.orders(tenant, c.id(), 30));
    }

    @Transactional
    public void signOut(String token) {
        if (Tokens.wellFormed(token)) sessions.delete(Tokens.sha256(token));
    }

    @Scheduled(cron = "0 17 4 * * *", zone = "America/Lima")
    @Transactional
    public void purgeExpiredSessions() {
        sessions.purgeExpired();
    }
}
