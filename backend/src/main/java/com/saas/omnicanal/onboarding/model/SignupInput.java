package com.saas.omnicanal.onboarding.model;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Formulario de prueba gratis. "website" es un campo trampa para bots: las personas lo dejan vacío. */
public record SignupInput(@NotBlank @Size(max = 100) String storeName,
                          @NotBlank @Size(max = 120) String ownerName,
                          @NotBlank @Email @Size(max = 254) String email,
                          @Pattern(regexp = "^$|^\\+?[0-9 ]{6,20}$") String phone,
                          @Pattern(regexp = "^$|^[A-Z][A-Z0-9_]{1,31}$") String plan,
                          @AssertTrue boolean acceptTerms,
                          @Size(max = 200) String website) {}
