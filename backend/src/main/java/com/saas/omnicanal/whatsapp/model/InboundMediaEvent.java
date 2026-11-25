package com.saas.omnicanal.whatsapp.model;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Mensaje entrante con archivo, ya guardado sin el archivo. Después de confirmar la transacción se descarga del
 * proveedor y se guarda. {@code evolutionItem}: el item del webhook de Evolution; {@code cloudMediaId}: id de Cloud API.
 */
public record InboundMediaEvent(UUID tenant, String chat, String messageId, WhatsAppProvider provider, MediaKind kind,
                                String mimeType, JsonNode evolutionItem, String cloudMediaId) {}
