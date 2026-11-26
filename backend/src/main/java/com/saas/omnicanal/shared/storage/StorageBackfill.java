package com.saas.omnicanal.shared.storage;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Migración de archivos antiguos (opcional, {@code CLOUDINARY_BACKFILL=true}): al arrancar sube por lotes a Cloudinary los
 * archivos guardados en la base (fotos, logos, catálogos y multimedia de WhatsApp) y, solo cuando la subida terminó bien,
 * guarda la referencia y deja {@code content} en null. Un archivo que falla se salta y queda en la base como estaba.
 * Es idempotente: puede correr en cada arranque y solo toma filas sin {@code storage_url}.
 */
@Component
public class StorageBackfill {
    private static final Logger log = LoggerFactory.getLogger(StorageBackfill.class);
    private static final int BATCH = 20;

    private final JdbcTemplate db;
    private final MediaStorage storage;
    private final boolean enabled;

    public StorageBackfill(JdbcTemplate db, MediaStorage storage, @Value("${storage.backfill:false}") boolean enabled) {
        this.db = db;
        this.storage = storage;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        if (!enabled) return;
        if (!storage.enabled()) {
            log.warn("CLOUDINARY_BACKFILL=true pero Cloudinary no está configurado: no se migra nada.");
            return;
        }
        Thread worker = new Thread(this::runAll, "cloudinary-backfill");
        worker.setDaemon(true);
        worker.start();
    }

    /** Público para las pruebas (corre en el hilo que lo llama). Devuelve cuántos archivos subió. */
    public int runAll() {
        int total = 0;
        try {
            total += table("product_image", MediaStorage.Folder.PRODUCTS, "media_type", null);
            total += table("category_catalog_file", MediaStorage.Folder.CATALOGS, "media_type", null);
            total += table("whatsapp_media", MediaStorage.Folder.WHATSAPP_INBOUND, "mime_type", "file_name");
            total += logos();
            log.info("Cloudinary: migración de archivos antiguos terminada ({} archivos).", total);
        } catch (RuntimeException e) {
            log.warn("Cloudinary: la migración de archivos antiguos se detuvo: {}", e.getMessage());
        }
        return total;
    }

    private record Row(UUID id, UUID tenant, String mime, String fileName, byte[] content) {}

    private int table(String table, MediaStorage.Folder folder, String mimeColumn, String nameColumn) {
        int done = 0;
        UUID after = new UUID(0, 0);
        while (true) {
            List<Row> rows = db.query("SELECT id, tenant_id, " + mimeColumn + ", " + (nameColumn == null ? "NULL" : nameColumn)
                            + ", content FROM " + table + " WHERE storage_url IS NULL AND content IS NOT NULL AND id > ?"
                            + " ORDER BY id LIMIT " + BATCH,
                    (rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                            rs.getString(4), rs.getBytes(5)), after);
            if (rows.isEmpty()) return done;
            for (Row r : rows) {
                after = r.id();
                StoredFile f;
                try {
                    f = storage.upload(r.tenant(), folder, r.content(), r.mime(), r.fileName());
                } catch (RuntimeException e) {
                    log.warn("Cloudinary: no se pudo migrar {} {}: {}", table, r.id(), e.getMessage());
                    continue;
                }
                int updated = db.update("UPDATE " + table + " SET storage_url=?, storage_public_id=?, storage_resource_type=?,"
                                + " storage_bytes=?, content=NULL WHERE id=? AND storage_url IS NULL",
                        f.url(), f.publicId(), f.resourceType(), f.bytes(), r.id());
                if (updated == 1) done++;
                else storage.delete(f.publicId(), f.resourceType());
            }
        }
    }

    private int logos() {
        int done = 0;
        UUID after = new UUID(0, 0);
        while (true) {
            List<Row> rows = db.query("""
                    SELECT tenant_id, logo_media_type, logo FROM store_appearance
                     WHERE logo_storage_url IS NULL AND logo IS NOT NULL AND tenant_id > ? ORDER BY tenant_id LIMIT ?
                    """, (rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getObject(1, UUID.class), rs.getString(2), null,
                    rs.getBytes(3)), after, BATCH);
            if (rows.isEmpty()) return done;
            for (Row r : rows) {
                after = r.tenant();
                StoredFile f;
                try {
                    f = storage.upload(r.tenant(), MediaStorage.Folder.LOGO, r.content(), r.mime(), null);
                } catch (RuntimeException e) {
                    log.warn("Cloudinary: no se pudo migrar el logo de {}: {}", r.tenant(), e.getMessage());
                    continue;
                }
                // Solo si el logo no cambió mientras se subía.
                int updated = db.update("""
                        UPDATE store_appearance SET logo_storage_url=?, logo_storage_public_id=?, logo_storage_resource_type=?,
                               logo_storage_bytes=?, logo=NULL
                         WHERE tenant_id=? AND logo_storage_url IS NULL AND logo IS NOT NULL AND md5(logo) = md5(?)
                        """, f.url(), f.publicId(), f.resourceType(), f.bytes(), r.tenant(), r.content());
                if (updated == 1) done++;
                else storage.delete(f.publicId(), f.resourceType());
            }
        }
    }
}
