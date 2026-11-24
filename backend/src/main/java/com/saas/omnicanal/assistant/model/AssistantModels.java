package com.saas.omnicanal.assistant.model;

import java.math.BigDecimal;
import java.util.List;

public final class AssistantModels {
    private AssistantModels() {}

    public record Settings(boolean enabled, String instructions, int slaMinutes, long revision) {}

    /** Estado para el panel: si la IA puede responder y por qué no. */
    public record Status(boolean configured, boolean platformEnabled, boolean planIncluded, long usedThisMonth, Long monthlyLimit) {}

    public record Turn(String author, String text) {}

    public record StoreFacts(String storeName, String pickupAddress, String pickupHours, BigDecimal freeDeliveryMinimum,
                             boolean distanceDelivery, String catalogUrl) {}

    public record Item(String prenda, String categoria, String talla, String color, BigDecimal precio, int stock, String enlace) {}

    /** Respuesta de la IA: texto para el cliente y, si corresponde, pedido de escalamiento con un resumen para el equipo. */
    public record Answer(String text, boolean escalate, String reason, String summary, List<String> toolsUsed) {}
}
