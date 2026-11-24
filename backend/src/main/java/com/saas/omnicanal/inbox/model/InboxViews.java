package com.saas.omnicanal.inbox.model;

import java.time.Instant;
import java.util.List;

public final class InboxViews {
    private InboxViews() {}

    public enum Status { OPEN, ESCALATED, CLOSED }

    /** {@code overdue}: escalada, el cliente escribió último y pasó el tiempo de respuesta (SLA) de la tienda. */
    public record Row(String chat, String phone, String contactName, Status status, boolean aiEnabled, String assignee,
                      String lastMessage, String lastAuthor, Instant lastInboundAt, Instant lastOutboundAt,
                      Instant escalatedAt, boolean overdue) {}

    public record Message(String author, String body, Instant at, String status) {}

    public record Conversation(Row row, String escalationReason, String summary, String handbackNote, long revision,
                               List<Message> messages) {}

    public record Counts(long open, long escalated, long overdue) {}
}
