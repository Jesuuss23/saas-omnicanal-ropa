package com.saas.omnicanal.whatsapp.repository;

import com.saas.omnicanal.shared.storage.StoredFile;
import com.saas.omnicanal.whatsapp.model.MediaKind;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Archivos de WhatsApp. Se exponen como {@code /media/{id}} dentro de la API del panel; con Cloudinary la base guarda
 * solo la referencia ({@code storage_url}) y el contenido queda en null.
 */
@Repository
public class WhatsAppMediaRepository {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    private final JdbcTemplate db;

    public WhatsAppMediaRepository(JdbcTemplate db) {
        this.db = db;
    }

    /** {@code content} es null cuando el archivo está en Cloudinary ({@code url}). */
    public record Media(MediaKind kind, String mimeType, String fileName, byte[] content, String url) {}
    public record StoredRef(String publicId, String resourceType) {}

    public static String url(UUID id) {
        return "/media/" + id;
    }

    public UUID store(UUID tenant, MediaKind kind, String mimeType, String fileName, byte[] content) {
        return store(tenant, kind, mimeType, fileName, content, null);
    }

    /**
     * Guarda el archivo: con {@code stored} solo la referencia de Cloudinary; sin ella, el contenido en la base. El hash
     * del contenido se guarda siempre (caché de transcripción, moderación y visión).
     */
    public UUID store(UUID tenant, MediaKind kind, String mimeType, String fileName, byte[] content, StoredFile stored) {
        return db.queryForObject("""
                INSERT INTO whatsapp_media(tenant_id, kind, mime_type, file_name, content, content_sha256,
                                           storage_url, storage_public_id, storage_resource_type, storage_bytes)
                VALUES (?,?,left(?,100),left(?,200),?,?,?,?,?,?) RETURNING id
                """, UUID.class, tenant, kind.name(), mimeType, fileName, stored == null ? content : null, sha256(content),
                stored == null ? null : stored.url(), stored == null ? null : stored.publicId(),
                stored == null ? null : stored.resourceType(), stored == null ? null : stored.bytes());
    }

    public static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Resultados ya calculados de un archivo. Si el mismo contenido llegó antes a la tienda (un reenvío), hereda sus
     * resultados: así nunca se vuelve a llamar a Groq, Sightengine ni al modelo de visión por el mismo archivo.
     */
    public record Analysis(String transcript, String transcriptStatus, String moderationStatus, java.math.BigDecimal moderationScore,
                           String visionStatus, String visionJson) {
        public static final Analysis NONE = new Analysis(null, null, null, null, null, null);
    }

    public Analysis analysis(UUID tenant, UUID id) {
        return db.query("""
                SELECT m.transcript, m.transcript_status, m.moderation_status, m.moderation_score, m.vision_status, m.vision_analysis_json::text
                  FROM whatsapp_media m WHERE m.tenant_id = ? AND m.id = ?
                """, (rs, n) -> new Analysis(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                        rs.getString(5), rs.getString(6)), tenant, id).stream().findFirst().orElse(Analysis.NONE);
    }

    /** Copia al archivo {@code id} los resultados de otro archivo idéntico de la tienda que ya terminó (DONE/SAFE/BLOCKED). */
    public void inheritFromSameContent(UUID tenant, UUID id) {
        db.update("""
                UPDATE whatsapp_media t SET
                       transcript = coalesce(t.transcript, s.transcript),
                       transcript_status = coalesce(t.transcript_status, CASE WHEN s.transcript_status IN ('DONE','EMPTY') THEN s.transcript_status END),
                       moderation_status = coalesce(t.moderation_status, CASE WHEN s.moderation_status IN ('SAFE','BLOCKED_NSFW') THEN s.moderation_status END),
                       moderation_score = coalesce(t.moderation_score, CASE WHEN s.moderation_status IN ('SAFE','BLOCKED_NSFW') THEN s.moderation_score END),
                       vision_status = coalesce(t.vision_status, CASE WHEN s.vision_status = 'DONE' THEN 'DONE' END),
                       vision_analysis_json = coalesce(t.vision_analysis_json, CASE WHEN s.vision_status = 'DONE' THEN s.vision_analysis_json END)
                  FROM (SELECT * FROM whatsapp_media WHERE tenant_id = ? AND id <> ?
                          AND content_sha256 = (SELECT content_sha256 FROM whatsapp_media WHERE tenant_id = ? AND id = ?)
                          AND (transcript_status IN ('DONE','EMPTY') OR moderation_status IN ('SAFE','BLOCKED_NSFW') OR vision_status = 'DONE')
                         ORDER BY processed_at DESC NULLS LAST LIMIT 1) s
                 WHERE t.tenant_id = ? AND t.id = ?
                """, tenant, id, tenant, id, tenant, id);
    }

    public void saveTranscript(UUID tenant, UUID id, String status, String transcript) {
        db.update("UPDATE whatsapp_media SET transcript_status=?, transcript=left(?,8000), processed_at=now() WHERE tenant_id=? AND id=? AND transcript_status IS NULL",
                status, transcript, tenant, id);
    }

    public void saveModeration(UUID tenant, UUID id, String status, Double score) {
        db.update("UPDATE whatsapp_media SET moderation_status=?, moderation_score=?, processed_at=now() WHERE tenant_id=? AND id=? AND moderation_status IS NULL",
                status, score, tenant, id);
    }

    public void saveVision(UUID tenant, UUID id, String status, String json) {
        db.update("UPDATE whatsapp_media SET vision_status=?, vision_analysis_json=CAST(? AS jsonb), processed_at=now() WHERE tenant_id=? AND id=? AND vision_status IS NULL",
                status, json, tenant, id);
    }

    /** Texto que la IA lee en lugar del cuerpo del mensaje (transcripción o contexto visual). */
    public void setAiContext(UUID tenant, String chat, String messageId, String context) {
        db.update("UPDATE whatsapp_inbound_message SET ai_context=left(?,4000) WHERE tenant_id=? AND chat_id=? AND message_id=?",
                context, tenant, chat, messageId);
    }

    public void markBlocked(UUID tenant, String chat, String messageId) {
        db.update("UPDATE whatsapp_inbound_message SET media_status='BLOCKED_NSFW' WHERE tenant_id=? AND chat_id=? AND message_id=?",
                tenant, chat, messageId);
    }

    /** Registra una incidencia una sola vez por mensaje; true si es nueva. */
    public boolean incident(UUID tenant, String chat, String messageId, UUID mediaId, String kind, Double score) {
        return db.update("""
                INSERT INTO whatsapp_media_incident(tenant_id, chat_id, message_id, media_id, kind, score) VALUES (?,?,?,?,?,?)
                ON CONFLICT (tenant_id, chat_id, message_id, kind) DO NOTHING
                """, tenant, chat, messageId, mediaId, kind, score) == 1;
    }

    public record Inbound(String body, String caption) {}

    public Optional<Inbound> inbound(UUID tenant, String chat, String messageId) {
        return db.query("SELECT body, caption FROM whatsapp_inbound_message WHERE tenant_id=? AND chat_id=? AND message_id=?",
                (rs, n) -> new Inbound(rs.getString(1), rs.getString(2)), tenant, chat, messageId).stream().findFirst();
    }

    public boolean blocked(UUID tenant, UUID id) {
        return Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM whatsapp_media WHERE tenant_id=? AND id=? AND moderation_status='BLOCKED_NSFW')", Boolean.class, tenant, id));
    }

    public Optional<Media> read(UUID tenant, UUID id) {
        return db.query("SELECT kind, mime_type, file_name, content, storage_url FROM whatsapp_media WHERE tenant_id=? AND id=?",
                (rs, n) -> new Media(MediaKind.valueOf(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getBytes(4), rs.getString(5)),
                tenant, id).stream().findFirst();
    }

    /** Borra la fila; devuelve su archivo de Cloudinary (si tenía) para borrarlo allá. */
    public Optional<StoredRef> delete(UUID tenant, UUID id) {
        return db.query("DELETE FROM whatsapp_media WHERE tenant_id=? AND id=? RETURNING storage_public_id, storage_resource_type",
                (rs, n) -> new StoredRef(rs.getString(1), rs.getString(2)), tenant, id).stream()
                .filter(r -> r.publicId() != null).findFirst();
    }

    /** Resultado de la descarga de un archivo entrante: STORED (con url), FAILED o TOO_LARGE. */
    public void finishInbound(UUID tenant, String chat, String messageId, String status, String url, String mimeType) {
        db.update("""
                UPDATE whatsapp_inbound_message SET media_status=?, media_url=?, mime_type=coalesce(left(?,100), mime_type)
                 WHERE tenant_id=? AND chat_id=? AND message_id=?
                """, status, url, mimeType, tenant, chat, messageId);
    }
}
