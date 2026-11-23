package com.saas.omnicanal.catalog.repository;

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

    public record ImageRef(UUID id, int position, String mediaType, int bytes) {}
    public record ImageData(String mediaType, byte[] content) {}

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
                SELECT id, position, media_type, octet_length(content) FROM product_image
                 WHERE tenant_id=? AND product_id=? ORDER BY position
                """, (rs, n) -> new ImageRef(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getInt(4)),
                tenant, product);
    }

    public UUID insert(UUID tenant, UUID product, int position, String mediaType, byte[] content) {
        return db.queryForObject("""
                INSERT INTO product_image(tenant_id, product_id, position, media_type, content) VALUES (?,?,?,?,?) RETURNING id
                """, UUID.class, tenant, product, position, mediaType, content);
    }

    public boolean delete(UUID tenant, UUID product, UUID image) {
        return db.update("DELETE FROM product_image WHERE tenant_id=? AND product_id=? AND id=?", tenant, product, image) == 1;
    }

    public void setPosition(UUID tenant, UUID product, UUID image, int position) {
        db.update("UPDATE product_image SET position=? WHERE tenant_id=? AND product_id=? AND id=?", position, tenant, product, image);
    }

    public Optional<ImageData> read(UUID tenant, UUID image) {
        return db.query("SELECT media_type, content FROM product_image WHERE tenant_id=? AND id=?",
                (rs, n) -> new ImageData(rs.getString(1), rs.getBytes(2)), tenant, image).stream().findFirst();
    }
}
