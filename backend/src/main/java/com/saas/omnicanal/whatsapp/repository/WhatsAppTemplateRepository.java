package com.saas.omnicanal.whatsapp.repository;

import com.saas.omnicanal.whatsapp.model.WhatsAppTemplate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WhatsAppTemplateRepository {
    private final JdbcTemplate db;

    public WhatsAppTemplateRepository(JdbcTemplate db) {
        this.db = db;
    }

    public record Row(String id, String name, String language, String category, String status, String componentsJson) {}

    /** Reemplaza el snapshot de plantillas de la tienda por el resultado de la última sincronización. */
    public void replaceAll(UUID tenant, List<Row> rows) {
        db.update("DELETE FROM whatsapp_cloud_template WHERE tenant_id=?", tenant);
        db.batchUpdate("""
                INSERT INTO whatsapp_cloud_template(tenant_id,template_id,name,language,category,status,components)
                VALUES (?,?,?,?,?,?,CAST(? AS jsonb))
                """, rows, 100, (ps, r) -> {
                    ps.setObject(1, tenant);
                    ps.setString(2, r.id());
                    ps.setString(3, r.name());
                    ps.setString(4, r.language());
                    ps.setString(5, r.category());
                    ps.setString(6, r.status());
                    ps.setString(7, r.componentsJson());
                });
    }

    public List<WhatsAppTemplate> list(UUID tenant) {
        return db.query("""
                SELECT template_id,name,language,category,status,
                       (SELECT c->>'text' FROM jsonb_array_elements(components) c WHERE c->>'type'='BODY' LIMIT 1),
                       components::text, synced_at
                  FROM whatsapp_cloud_template WHERE tenant_id=? ORDER BY name,language
                """, (rs, n) -> {
                    String body = rs.getString(6);
                    return new WhatsAppTemplate(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), body, countParameters(body), rs.getString(7),
                            rs.getObject(8, OffsetDateTime.class));
                }, tenant);
    }

    public boolean approvedExists(UUID tenant, String name, String language) {
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM whatsapp_cloud_template
                 WHERE tenant_id=? AND name=? AND language=? AND status='APPROVED')
                """, Boolean.class, tenant, name, language));
    }

    static int countParameters(String body) {
        if (body == null) return 0;
        var m = java.util.regex.Pattern.compile("\\{\\{\\s*(\\d+)\\s*}}").matcher(body);
        int max = 0;
        while (m.find()) max = Math.max(max, Integer.parseInt(m.group(1)));
        return max;
    }
}
