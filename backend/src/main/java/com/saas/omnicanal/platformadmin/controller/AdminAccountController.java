package com.saas.omnicanal.platformadmin.controller;

import com.saas.omnicanal.auth.model.MfaSetup;
import com.saas.omnicanal.auth.model.MfaStatus;
import com.saas.omnicanal.auth.model.MfaSubject;
import com.saas.omnicanal.auth.service.MfaService;
import com.saas.omnicanal.auth.service.SessionUpgrade;
import com.saas.omnicanal.platformadmin.service.AdminLogin;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Perfil del superadmin y enrolamiento de su app autenticadora con QR. */
@RestController
@RequestMapping("/api/superadmin")
class AdminAccountController {
    private final MfaService mfa;
    private final AdminLogin login;
    private final SessionUpgrade sessions;

    AdminAccountController(MfaService mfa, AdminLogin login, SessionUpgrade sessions) {
        this.mfa = mfa;
        this.login = login;
        this.sessions = sessions;
    }

    record Me(String email, boolean setupRequired, MfaStatus mfa, boolean legacySecret) {}
    record Code(@NotBlank @Size(max = 20) String code) {}

    @GetMapping("/me")
    Me me(Authentication auth) {
        MfaStatus status = mfa.status(MfaSubject.SUPERADMIN, login.email());
        return new Me(login.email(), setupOnly(auth), status, !status.enabled() && login.usesEnvironmentSecret());
    }

    @PostMapping("/mfa/setup")
    MfaSetup setup() {
        return mfa.startSetup(MfaSubject.SUPERADMIN, login.email(), login.email());
    }

    @PostMapping("/mfa/confirm")
    Map<String, List<String>> confirm(@Valid @RequestBody Code input, Authentication auth,
                                      HttpServletRequest request, HttpServletResponse response) {
        List<String> codes = mfa.confirmSetup(MfaSubject.SUPERADMIN, login.email(), input.code());
        if (setupOnly(auth)) sessions.grant(auth, List.of(AdminLogin.FULL_ROLE), request, response);
        return Map.of("recoveryCodes", codes);
    }

    @PostMapping("/mfa/recovery-codes")
    Map<String, List<String>> recoveryCodes(@Valid @RequestBody Code input) {
        if (!mfa.verify(MfaSubject.SUPERADMIN, login.email(), input.code())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El código no es válido.");
        }
        return Map.of("recoveryCodes", mfa.regenerateRecoveryCodes(MfaSubject.SUPERADMIN, login.email()));
    }

    private static boolean setupOnly(Authentication auth) {
        return auth.getAuthorities().stream().noneMatch(a -> AdminLogin.FULL_ROLE.equals(a.getAuthority()));
    }
}
