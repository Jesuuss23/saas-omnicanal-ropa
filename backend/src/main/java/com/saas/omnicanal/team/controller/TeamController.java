package com.saas.omnicanal.team.controller;

import com.saas.omnicanal.team.model.Member;
import com.saas.omnicanal.team.model.StoreRole;
import com.saas.omnicanal.team.service.TeamService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import static com.saas.omnicanal.shared.security.OwnerAccess.requireOwner;

/** Equipo de la tienda: solo el propietario lo administra (ver OwnerSecurity). */
@RestController
class TeamController {
    private final TeamService team;

    TeamController(TeamService team) {
        this.team = team;
    }

    record Invite(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 120) String fullName, @NotNull StoreRole role) {}
    record Change(@NotNull StoreRole role, @NotNull Boolean active) {}
    record Activation(@NotNull UUID store, @NotNull UUID member, @NotBlank String token, @NotNull @Size(min = 12, max = 72) String password) {}

    @GetMapping("/api/owner/stores/{store}/team")
    TeamService.Team list(@PathVariable UUID store, Principal principal) {
        requireOwner(store, principal);
        return team.team(store);
    }

    @PostMapping("/api/owner/stores/{store}/team")
    @ResponseStatus(HttpStatus.CREATED)
    TeamService.Invitation invite(@PathVariable UUID store, Principal principal, @Valid @RequestBody Invite in) {
        requireOwner(store, principal);
        return team.invite(store, in.email(), in.fullName(), in.role());
    }

    @PostMapping("/api/owner/stores/{store}/team/{member}/invitation")
    TeamService.Invitation resend(@PathVariable UUID store, @PathVariable UUID member, Principal principal) {
        requireOwner(store, principal);
        return team.resend(store, member);
    }

    @PutMapping("/api/owner/stores/{store}/team/{member}")
    Member update(@PathVariable UUID store, @PathVariable UUID member, Principal principal, @Valid @RequestBody Change in) {
        requireOwner(store, principal);
        return team.update(store, member, in.role(), in.active());
    }

    @PostMapping("/api/owner/members/activate")
    Map<String, Boolean> activate(@Valid @RequestBody Activation in) {
        team.activate(in.store(), in.member(), in.token(), in.password());
        return Map.of("activated", true);
    }
}
