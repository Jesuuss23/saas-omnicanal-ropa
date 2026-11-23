package com.saas.omnicanal.platformadmin.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Cambio manual de suscripción hecho por el superadmin.
 * EXTEND_TRIAL usa {@code days}; ACTIVATE usa {@code planCode} y {@code months} (pago fuera de Culqi); CANCEL no usa más campos.
 */
public record SubscriptionChange(@NotNull Action action, @Pattern(regexp = "[A-Z0-9_]{2,32}") String planCode,
                                 @Min(1) @Max(90) Integer days, @Min(1) @Max(24) Integer months,
                                 @NotBlank @Size(max = 200) String reason) {
    public enum Action { EXTEND_TRIAL, ACTIVATE, CANCEL }
}
