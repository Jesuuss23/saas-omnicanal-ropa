package com.saas.omnicanal.whatsapp.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Fila de whatsapp_cloud_credential tal como está en la base (secretos cifrados). */
public record CloudApiCredentialRecord(UUID tenantId, String phoneNumberId, String wabaId,
                                       byte[] accessTokenCiphertext, byte[] appSecretCiphertext,
                                       String displayPhoneNumber, String verifiedName, String qualityRating,
                                       String status, String lastError, OffsetDateTime verifiedAt, long revision) {
}
