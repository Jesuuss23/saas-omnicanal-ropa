package com.saas.omnicanal.tenant.repository;

import com.saas.omnicanal.shared.storage.StoredFile;
import com.saas.omnicanal.tenant.model.Appearance;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AppearanceRepository {
    private final JdbcTemplate db;

    public AppearanceRepository(JdbcTemplate db) {
        this.db = db;
    }

    /** {@code content} es null cuando el logo está en Cloudinary ({@code url}). */
    public record Logo(String mediaType, byte[] content, String url) {}
    public record StoredRef(String publicId, String resourceType) {}

    public Optional<Appearance> find(UUID tenant) {
        return db.query("""
                SELECT t.name, a.tagline, a.description, coalesce(a.primary_color, ?), a.whatsapp_phone, a.instagram,
                       (a.logo IS NOT NULL OR a.logo_storage_url IS NOT NULL), coalesce(a.logo_version, 0),
                       coalesce(a.revision, 0), a.logo_storage_url
                FROM tenant t LEFT JOIN store_appearance a ON a.tenant_id = t.id WHERE t.id = ?
                """, (rs, n) -> new Appearance(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4).trim(),
                rs.getString(5), rs.getString(6), rs.getBoolean(7), rs.getInt(8), rs.getLong(9), rs.getString(10)),
                Appearance.DEFAULT_COLOR, tenant)
                .stream().findFirst();
    }

    public void ensureRow(UUID tenant) {
        db.update("INSERT INTO store_appearance(tenant_id) VALUES (?) ON CONFLICT DO NOTHING", tenant);
    }

    /** Guarda si la revisión coincide (edición optimista); devuelve false si otro cambio llegó antes. */
    public boolean update(UUID tenant, long revision, String tagline, String description, String color, String phone, String instagram) {
        return db.update("""
                UPDATE store_appearance SET tagline=?, description=?, primary_color=?, whatsapp_phone=?, instagram=?,
                       revision=revision+1, updated_at=now()
                 WHERE tenant_id=? AND revision=?
                """, tagline, description, color, phone, instagram, tenant, revision) == 1;
    }

    public void renameStore(UUID tenant, String name) {
        db.update("UPDATE tenant SET name=? WHERE id=?", name, tenant);
    }

    /**
     * Cambia o quita el logo: con {@code stored} guarda solo la referencia de Cloudinary; sin ella, el contenido en la base.
     * Devuelve el archivo de Cloudinary del logo anterior (para borrarlo allá).
     */
    public Optional<StoredRef> setLogo(UUID tenant, String mediaType, byte[] content, StoredFile stored) {
        var previous = db.query("""
                SELECT logo_storage_public_id, logo_storage_resource_type FROM store_appearance
                 WHERE tenant_id=? AND logo_storage_public_id IS NOT NULL FOR UPDATE
                """, (rs, n) -> new StoredRef(rs.getString(1), rs.getString(2)), tenant).stream().findFirst();
        db.update("""
                UPDATE store_appearance SET logo_media_type=?, logo=?, logo_storage_url=?, logo_storage_public_id=?,
                       logo_storage_resource_type=?, logo_storage_bytes=?, logo_version=logo_version+1,
                       revision=revision+1, updated_at=now() WHERE tenant_id=?
                """, mediaType, stored == null ? content : null, stored == null ? null : stored.url(),
                stored == null ? null : stored.publicId(), stored == null ? null : stored.resourceType(),
                stored == null ? null : stored.bytes(), tenant);
        return previous;
    }

    public Optional<Logo> logo(UUID tenant) {
        return db.query("""
                SELECT logo_media_type, logo, logo_storage_url FROM store_appearance
                 WHERE tenant_id=? AND (logo IS NOT NULL OR logo_storage_url IS NOT NULL)
                """, (rs, n) -> new Logo(rs.getString(1), rs.getBytes(2), rs.getString(3)), tenant).stream().findFirst();
    }
}
