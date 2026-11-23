package com.saas.omnicanal.auth.model;

/**
 * Datos para enrolar una app autenticadora: el secreto en Base32 (para escribirlo a mano),
 * la URI otpauth:// y la misma URI como imagen QR (data URL PNG).
 */
public record MfaSetup(String secret, String otpauthUri, String qrDataUrl) {}
