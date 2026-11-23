package com.saas.omnicanal.fiscal.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Perfil fiscal del emisor (paso 1 del alta de facturación). */
public record FiscalProfile(@NotBlank @Pattern(regexp="[0-9]{11}") String ruc,
        @NotBlank @Size(max=200) String legalName,
        @NotBlank @Size(max=250) String address,
        @NotBlank @Pattern(regexp="[0-9]{6}") String ubigeo,
        @NotNull @PositiveOrZero Integer revision) {}
