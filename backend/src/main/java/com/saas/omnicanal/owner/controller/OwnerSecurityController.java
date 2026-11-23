package com.saas.omnicanal.owner.controller;

import com.saas.omnicanal.auth.model.MfaSetup;
import com.saas.omnicanal.auth.model.MfaStatus;
import com.saas.omnicanal.auth.model.MfaSubject;
import com.saas.omnicanal.auth.service.MfaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Seguridad de la cuenta del propietario: verificación en dos pasos con app autenticadora. */
@RestController
@RequestMapping("/api/owner/security")
class OwnerSecurityController {
    private final MfaService mfa;
    private final JdbcTemplate db;

    OwnerSecurityController(MfaService mfa, JdbcTemplate db) {
        this.mfa = mfa;
        this.db = db;
    }

    record Security(String email, MfaStatus mfa) {}
    record Code(@NotBlank @Size(max = 20) String code) {}

    @GetMapping
    Security security(Principal principal) {
        return new Security(email(principal), mfa.status(MfaSubject.OWNER, principal.getName()));
    }

    @PostMapping("/mfa/setup")
    MfaSetup setup(Principal principal) {
        return mfa.startSetup(MfaSubject.OWNER, principal.getName(), email(principal));
    }

    @PostMapping("/mfa/confirm")
    Map<String, List<String>> confirm(@Valid @RequestBody Code input, Principal principal) {
        return Map.of("recoveryCodes", mfa.confirmSetup(MfaSubject.OWNER, principal.getName(), input.code()));
    }

    @PostMapping("/mfa/recovery-codes")
    Map<String, List<String>> recoveryCodes(@Valid @RequestBody Code input, Principal principal) {
        requireCode(principal, input.code());
        return Map.of("recoveryCodes", mfa.regenerateRecoveryCodes(MfaSubject.OWNER, principal.getName()));
    }

    @PostMapping("/mfa/disable")
    Map<String, Boolean> disable(@Valid @RequestBody Code input, Principal principal) {
        requireCode(principal, input.code());
        mfa.disable(MfaSubject.OWNER, principal.getName());
        return Map.of("disabled", true);
    }

    private void requireCode(Principal principal, String code) {
        if (!mfa.verify(MfaSubject.OWNER, principal.getName(), code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El código no es válido.");
        }
    }

    private String email(Principal principal) {
        return db.queryForObject("SELECT email FROM tenant_owner WHERE tenant_id = ?", String.class,
                UUID.fromString(principal.getName()));
    }
}
