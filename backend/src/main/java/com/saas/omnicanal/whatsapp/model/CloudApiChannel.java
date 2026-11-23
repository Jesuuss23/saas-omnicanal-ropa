package com.saas.omnicanal.whatsapp.model;

import java.time.OffsetDateTime;

/** Vista segura de la conexión Cloud API para el panel del dueño (sin secretos). */
public record CloudApiChannel(boolean configured, String phoneNumberId, String wabaId,
                              boolean accessTokenConfigured, boolean appSecretConfigured,
                              String displayPhoneNumber, String verifiedName, String qualityRating,
                              String status, String lastError, OffsetDateTime verifiedAt, long revision,
                              String webhookUrl, String webhookVerifyToken, boolean encryptionReady) {
}
