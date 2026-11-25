package com.saas.omnicanal.whatsapp.repository;

import com.saas.omnicanal.whatsapp.model.MediaKind;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Archivos de WhatsApp guardados en la base. Se exponen como {@code /media/{id}} dentro de la API del panel. */
@Repository
public class WhatsAppMediaRepository {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    private final JdbcTemplate db;

    public WhatsAppMediaRepository(JdbcTemplate db) {
        this.db = db;
    }

    public record Media(MediaKind kind, String mimeType, String fileName, byte[] content) {}

    public static String url(UUID id) {
        return "/media/" + id;
    }

    public UUID store(UUID tenant, MediaKind kind, String mimeType, String fileName, byte[] content) {
        return db.queryForObject("""
                INSERT INTO whatsapp_media(tenant_id, kind, mime_type, file_name, content) VALUES (?,?,left(?,100),left(?,200),?) RETURNING id
                """, UUID.class, tenant, kind.name(), mimeType, fileName, content);
    }

    public Optional<Media> read(UUID tenant, UUID id) {
        return db.query("SELECT kind, mime_type, file_name, content FROM whatsapp_media WHERE tenant_id=? AND id=?",
                (rs, n) -> new Media(MediaKind.valueOf(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getBytes(4)),
                tenant, id).stream().findFirst();
    }

    public void delete(UUID tenant, UUID id) {
        db.update("DELETE FROM whatsapp_media WHERE tenant_id=? AND id=?", tenant, id);
    }

    /** Resultado de la descarga de un archivo entrante: STORED (con url), FAILED o TOO_LARGE. */
    public void finishInbound(UUID tenant, String chat, String messageId, String status, String url, String mimeType) {
        db.update("""
                UPDATE whatsapp_inbound_message SET media_status=?, media_url=?, mime_type=coalesce(left(?,100), mime_type)
                 WHERE tenant_id=? AND chat_id=? AND message_id=?
                """, status, url, mimeType, tenant, chat, messageId);
    }
}
