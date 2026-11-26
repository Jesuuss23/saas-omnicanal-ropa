package com.saas.omnicanal.inbox.model;

import java.time.Instant;
import java.util.List;

public final class InboxViews {
    private InboxViews() {}

    public enum Status { OPEN, ESCALATED, CLOSED }

    /**
     * {@code overdue}: escalada, el cliente escribió último y pasó el tiempo de respuesta (SLA) de la tienda.
     * {@code aiPausedReason}: OFF_TOPIC si la IA se pausó sola tras preguntas ajenas a la tienda, o null.
     */
    public record Row(String chat, String phone, String contactName, Status status, boolean aiEnabled, String assignee,
                      String lastMessage, String lastAuthor, Instant lastInboundAt, Instant lastOutboundAt,
                      Instant escalatedAt, boolean overdue, String aiPausedReason, int offTopicCount) {}

    /** {@code mediaType}: IMAGE, VIDEO, AUDIO o DOCUMENT; {@code mediaStatus} solo en entrantes (PENDING, STORED, FAILED, TOO_LARGE). */
    /**
     * {@code transcript}: texto de una nota de voz. {@code moderation}: SAFE, BLOCKED_NSFW, FAILED o DISABLED.
     * {@code vision}: atributos detectados en la foto y prendas parecidas del catálogo (JSON guardado en caché).
     * {@code aiContext}: lo que la IA leyó en lugar del cuerpo (solo para el historial del modelo).
     * {@code fileUrl}: secure_url de Cloudinary del archivo (null si sigue en la base o si la imagen está bloqueada por
     * contenido explícito; esa solo se ve con {@code mediaUrl?reveal=true}). {@code mediaUrl} es siempre /media/{id}.
     */
    public record Message(String author, String body, Instant at, String status, String mediaType, String mimeType,
                          String mediaUrl, String mediaStatus, String caption, String transcript, String moderation,
                          tools.jackson.databind.JsonNode vision, @com.fasterxml.jackson.annotation.JsonIgnore String aiContext,
                          String fileUrl) {
        public Message(String author, String body, Instant at, String status) {
            this(author, body, at, status, null, null, null, null, null, null, null, null, null, null);
        }
    }

    public record Conversation(Row row, String escalationReason, String summary, String handbackNote, long revision,
                               List<Message> messages) {}

    public record Counts(long open, long escalated, long overdue) {}
}
