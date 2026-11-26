package com.saas.omnicanal.catalog.repository;

import com.saas.omnicanal.shared.storage.StoredFile;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProductImageRepository {
    private final JdbcTemplate db;

    public ProductImageRepository(JdbcTemplate db) {
        this.db = db;
    }

    /** {@code url}: secure_url de Cloudinary (null si la foto está guardada en la base). */
    public record ImageRef(UUID id, int position, String mediaType, int bytes, String url) {}
    /** {@code content} es null cuando la foto está en Cloudinary ({@code url}). */
    public record ImageData(String mediaType, byte[] content, String url) {}
    public record StorageRef(String publicId, String resourceType) {}

    public boolean productExists(UUID tenant, UUID product) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM product WHERE tenant_id=? AND id=?)",
                Boolean.class, tenant, product));
    }

    /** Bloquea el producto para que dos subidas simultáneas no tomen la misma posición. */
    public void lockProduct(UUID tenant, UUID product) {
        db.query("SELECT id FROM product WHERE tenant_id=? AND id=? FOR UPDATE", rs -> {}, tenant, product);
    }

    public List<ImageRef> list(UUID tenant, UUID product) {
        return db.query("""
                SELECT id, position, media_type, coalesce(octet_length(content), storage_bytes, 0), storage_url FROM product_image
                 WHERE tenant_id=? AND product_id=? ORDER BY position
                """, (rs, n) -> new ImageRef(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getInt(4), rs.getString(5)),
                tenant, product);
    }

    /** Guarda la foto: con {@code stored} solo la referencia de Cloudinary; sin ella, el contenido en la base. */
    public UUID insert(UUID tenant, UUID product, int position, String mediaType, byte[] content, StoredFile stored) {
        return db.queryForObject("""
                INSERT INTO product_image(tenant_id, product_id, position, media_type, content,
                                          storage_url, storage_public_id, storage_resource_type, storage_bytes)
                VALUES (?,?,?,?,?,?,?,?,?) RETURNING id
                """, UUID.class, tenant, product, position, mediaType, stored == null ? content : null,
                stored == null ? null : stored.url(), stored == null ? null : stored.publicId(),
                stored == null ? null : stored.resourceType(), stored == null ? null : stored.bytes());
    }

    public Optional<StorageRef> storage(UUID tenant, UUID product, UUID image) {
        return db.query("""
                SELECT storage_public_id, storage_resource_type FROM product_image
                 WHERE tenant_id=? AND product_id=? AND id=? AND storage_public_id IS NOT NULL
                """, (rs, n) -> new StorageRef(rs.getString(1), rs.getString(2)), tenant, product, image).stream().findFirst();
    }

    public boolean delete(UUID tenant, UUID product, UUID image) {
        return db.update("DELETE FROM product_image WHERE tenant_id=? AND product_id=? AND id=?", tenant, product, image) == 1;
    }

    public void setPosition(UUID tenant, UUID product, UUID image, int position) {
        db.update("UPDATE product_image SET position=? WHERE tenant_id=? AND product_id=? AND id=?", position, tenant, product, image);
    }

    public Optional<ImageData> read(UUID tenant, UUID image) {
        return db.query("SELECT media_type, content, storage_url FROM product_image WHERE tenant_id=? AND id=?",
                (rs, n) -> new ImageData(rs.getString(1), rs.getBytes(2), rs.getString(3)), tenant, image).stream().findFirst();
    }
}
