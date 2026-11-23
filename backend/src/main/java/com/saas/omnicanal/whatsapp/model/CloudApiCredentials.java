package com.saas.omnicanal.whatsapp.model;

import java.util.UUID;

/** Credenciales descifradas de Cloud API. Solo viven en memoria del backend; nunca se serializan al cliente. */
public record CloudApiCredentials(UUID tenantId, String phoneNumberId, String wabaId, String accessToken, String appSecret) {
    public boolean hasAppSecret() {
        return appSecret != null && !appSecret.isBlank();
    }

    @Override
    public String toString() {
        return "CloudApiCredentials[tenant=" + tenantId + ", phoneNumberId=" + phoneNumberId + "]";
    }
}
