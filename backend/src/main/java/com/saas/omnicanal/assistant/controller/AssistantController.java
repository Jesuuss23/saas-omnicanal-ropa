package com.saas.omnicanal.assistant.controller;

import com.saas.omnicanal.assistant.model.AssistantModels.Answer;
import com.saas.omnicanal.assistant.model.AssistantModels.Settings;
import com.saas.omnicanal.assistant.model.AssistantModels.Status;
import com.saas.omnicanal.assistant.model.AssistantModels.Turn;
import com.saas.omnicanal.assistant.service.AssistantService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

import static com.saas.omnicanal.shared.security.OwnerAccess.requireOwner;

/** Configuración y prueba de la IA vendedora (propietario o encargado, ver OwnerSecurity). */
@RestController
@RequestMapping("/api/owner/stores/{store}/assistant")
class AssistantController {
    private final AssistantService assistant;

    AssistantController(AssistantService assistant) {
        this.assistant = assistant;
    }

    record Save(@NotNull Boolean enabled, @NotNull @Size(max = 2000) String instructions,
                @Min(1) @Max(1440) int slaMinutes, @Min(0) long revision) {}
    record TestTurn(@NotNull @Pattern(regexp = "CUSTOMER|AI") String author, @NotBlank @Size(max = 1000) String text) {}
    record Test(@NotBlank @Size(max = 1000) String message, @Size(max = 12) List<@Valid TestTurn> history) {}

    @GetMapping("/settings")
    Settings settings(@PathVariable UUID store, Principal principal) {
        requireOwner(store, principal);
        return assistant.settings(store);
    }

    @PutMapping("/settings")
    Settings save(@PathVariable UUID store, Principal principal, @Valid @RequestBody Save in) {
        requireOwner(store, principal);
        return assistant.save(store, in.revision(), in.enabled(), in.instructions(), in.slaMinutes());
    }

    @GetMapping("/status")
    Status status(@PathVariable UUID store, Principal principal) {
        requireOwner(store, principal);
        return assistant.status(store);
    }

    @PostMapping("/test")
    Answer test(@PathVariable UUID store, Principal principal, @Valid @RequestBody Test in) {
        requireOwner(store, principal);
        var history = in.history() == null ? List.<Turn>of() : in.history().stream().map(t -> new Turn(t.author(), t.text())).toList();
        return assistant.test(store, history, in.message());
    }
}
