package com.saas.omnicanal.platformadmin.controller;

import com.saas.omnicanal.assistant.repository.AssistantRepository;
import com.saas.omnicanal.platformadmin.service.AuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Interruptor global de la IA vendedora: apagarlo detiene las respuestas automáticas de todas las tiendas. */
@RestController
@RequestMapping("/api/superadmin/platform/ai")
class PlatformAiController {
    private final AssistantRepository assistant;
    private final AuditService audit;

    PlatformAiController(AssistantRepository assistant, AuditService audit) {
        this.assistant = assistant;
        this.audit = audit;
    }

    record Toggle(@NotNull Boolean enabled) {}

    @GetMapping
    Map<String, Boolean> get() {
        return Map.of("enabled", assistant.platformEnabled());
    }

    @PutMapping
    @Transactional
    Map<String, Boolean> set(@Valid @RequestBody Toggle in, Principal principal) {
        assistant.setPlatformEnabled(in.enabled(), principal.getName());
        audit.record(principal, AuditService.AI_PLATFORM_TOGGLED, null, in.enabled() ? "IA encendida" : "IA apagada");
        return Map.of("enabled", in.enabled());
    }
}
