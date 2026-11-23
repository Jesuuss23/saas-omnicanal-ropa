package com.saas.omnicanal.tenant.model;

/** Apariencia de la tienda pública. {@code logoVersion} cambia con cada logo nuevo para invalidar cachés. */
public record Appearance(String name, String tagline, String description, String primaryColor, String whatsappPhone,
                         String instagram, boolean hasLogo, int logoVersion, long revision) {
    public static final String DEFAULT_COLOR = "#0f766e";
}
