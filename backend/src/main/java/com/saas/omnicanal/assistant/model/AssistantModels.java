package com.saas.omnicanal.assistant.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.util.List;

public final class AssistantModels {
    private AssistantModels() {}

    public record Settings(boolean enabled, String instructions, int slaMinutes, long revision) {}

    /** Estado para el panel: si la IA puede responder y por qué no; {@code debounceMs} es la ventana de agrupación de mensajes. */
    public record Status(boolean configured, boolean platformEnabled, boolean planIncluded, long usedThisMonth, Long monthlyLimit,
                         long debounceMs) {}

    public record Turn(String author, String text) {}

    public record StoreFacts(String storeName, String pickupAddress, String pickupHours, BigDecimal freeDeliveryMinimum,
                             boolean distanceDelivery, String catalogUrl) {}

    public record Item(String prenda, String categoria, String subcategoria, String talla, String color, BigDecimal precio,
                       int stock, String enlace) {}

    /** Categoría con sus subcategorías (estilos) y cuántas prendas con stock hay; {@code catalogo}: si tiene recurso cargado. */
    public record Category(String categoria, int prendas, List<Subcategory> subcategorias, boolean catalogo) {}

    public record Subcategory(String nombre, int prendas) {}

    /** Archivo que la IA envía después del texto (PDF o imagen del catálogo de una categoría). */
    public record Attachment(String kind, String mimeType, String fileName, @JsonIgnore byte[] content, String description) {}

    /**
     * Respuesta de la IA: texto para el cliente y, si corresponde, pedido de escalamiento con un resumen para el equipo.
     * {@code offTopic}: el modelo marcó el mensaje como ajeno a la tienda. {@code paused}: la IA quedó (o ya estaba)
     * pausada en la conversación por preguntas fuera de tema.
     */
    public record Answer(String text, boolean escalate, String reason, String summary, List<String> toolsUsed,
                         boolean offTopic, boolean paused, List<Attachment> attachments) {
        public Answer {
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

        public Answer(String text, boolean escalate, String reason, String summary, List<String> toolsUsed,
                      boolean offTopic, boolean paused) {
            this(text, escalate, reason, summary, toolsUsed, offTopic, paused, List.of());
        }

        public Answer(String text, boolean escalate, String reason, String summary, List<String> toolsUsed) {
            this(text, escalate, reason, summary, toolsUsed, false, false, List.of());
        }
    }
}
