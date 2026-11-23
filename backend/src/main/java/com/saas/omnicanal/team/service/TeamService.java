package com.saas.omnicanal.team.service;

import com.saas.omnicanal.notifications.service.EmailService;
import com.saas.omnicanal.notifications.service.EmailTemplates;
import com.saas.omnicanal.shared.crypto.Tokens;
import com.saas.omnicanal.subscription.service.EntitlementService;
import com.saas.omnicanal.team.model.Member;
import com.saas.omnicanal.team.model.StoreRole;
import com.saas.omnicanal.team.repository.MemberRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Equipo de la tienda. La cuota {@code team.users} cuenta al propietario más los miembros activos. */
@Service
public class TeamService {
    public static final String QUOTA = "team.users";

    private final MemberRepository members;
    private final EntitlementService entitlements;
    private final EmailService email;
    private final EmailTemplates templates;
    private final PasswordEncoder passwords;

    public TeamService(MemberRepository members, EntitlementService entitlements, EmailService email, EmailTemplates templates,
                       PasswordEncoder passwords) {
        this.members = members;
        this.entitlements = entitlements;
        this.email = email;
        this.templates = templates;
        this.passwords = passwords;
    }

    public record Team(List<Member> members, long used, Long limit) {}
    public record Invitation(Member member, String link, boolean emailed) {}

    public Team team(UUID tenant) {
        return new Team(members.list(tenant), 1 + members.countActive(tenant), entitlements.get(tenant, QUOTA).limit());
    }

    @Transactional
    public Invitation invite(UUID tenant, String rawEmail, String name, StoreRole role) {
        if (role == StoreRole.OWNER) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La tienda tiene un solo propietario.");
        String address = rawEmail.strip().toLowerCase(Locale.ROOT);
        members.storeName(tenant).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (members.emailTaken(tenant, address))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ese correo ya es parte del equipo.");
        entitlements.requireQuota(tenant, QUOTA, 1 + members.countActive(tenant), 1);
        String token = Tokens.random();
        UUID id = members.insert(tenant, address, name.strip(), role, Tokens.sha256(token));
        return send(tenant, members.find(tenant, id).orElseThrow(), token);
    }

    @Transactional
    public Invitation resend(UUID tenant, UUID id) {
        String token = Tokens.random();
        if (!members.renewInvitation(tenant, id, Tokens.sha256(token)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ese miembro ya activó su acceso o está desactivado.");
        return send(tenant, members.find(tenant, id).orElseThrow(), token);
    }

    @Transactional
    public Member update(UUID tenant, UUID id, StoreRole role, boolean active) {
        if (role == StoreRole.OWNER) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La tienda tiene un solo propietario.");
        Member current = members.find(tenant, id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (active && !current.active()) entitlements.requireQuota(tenant, QUOTA, 1 + members.countActive(tenant), 1);
        members.update(tenant, id, role, active);
        return members.find(tenant, id).orElseThrow();
    }

    @Transactional
    public void activate(UUID tenant, UUID id, String token, String password) {
        if (!Tokens.wellFormed(token) || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if (!members.activate(tenant, id, Tokens.sha256(token), passwords.encode(password)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La invitación no es válida o ya venció.");
    }

    private Invitation send(UUID tenant, Member member, String token) {
        String store = members.storeName(tenant).orElse("");
        // El fragmento (#m.<id>.<token>) no viaja al servidor del frontend ni queda en registros de acceso.
        String link = templates.url("/propietario/" + tenant + "#m." + member.id() + "." + token);
        email.send(templates.memberInvitation(member.email(), member.fullName(), store,
                member.role() == StoreRole.MANAGER ? "encargado" : "vendedor", link));
        return new Invitation(member, link, email.configured());
    }
}
