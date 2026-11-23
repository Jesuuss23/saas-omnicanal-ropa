package com.saas.omnicanal.tenant.repository;

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

    public record Logo(String mediaType, byte[] content) {}

    public Optional<Appearance> find(UUID tenant) {
        return db.query("""
                SELECT t.name, a.tagline, a.description, coalesce(a.primary_color, ?), a.whatsapp_phone, a.instagram,
                       a.logo IS NOT NULL, coalesce(a.logo_version, 0), coalesce(a.revision, 0)
                FROM tenant t LEFT JOIN store_appearance a ON a.tenant_id = t.id WHERE t.id = ?
                """, (rs, n) -> new Appearance(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4).trim(),
                rs.getString(5), rs.getString(6), rs.getBoolean(7), rs.getInt(8), rs.getLong(9)), Appearance.DEFAULT_COLOR, tenant)
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

    public void setLogo(UUID tenant, String mediaType, byte[] content) {
        db.update("""
                UPDATE store_appearance SET logo_media_type=?, logo=?, logo_version=logo_version+1,
                       revision=revision+1, updated_at=now() WHERE tenant_id=?
                """, mediaType, content, tenant);
    }

    public Optional<Logo> logo(UUID tenant) {
        return db.query("SELECT logo_media_type, logo FROM store_appearance WHERE tenant_id=? AND logo IS NOT NULL",
                (rs, n) -> new Logo(rs.getString(1), rs.getBytes(2)), tenant).stream().findFirst();
    }
}
