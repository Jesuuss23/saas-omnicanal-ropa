package com.saas.omnicanal.catalog.repository;

import com.saas.omnicanal.catalog.model.PublicCatalog.Availability;
import com.saas.omnicanal.catalog.model.PublicCatalog.CategoryResource;
import com.saas.omnicanal.catalog.model.PublicCatalog.Filters;
import com.saas.omnicanal.catalog.model.PublicCatalog.Pickup;
import com.saas.omnicanal.catalog.model.PublicCatalog.Variant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PublicCatalogRepository {
    private static final String MATCHES = """
              FROM product p JOIN product_variant v ON (v.tenant_id, v.product_id) = (p.tenant_id, p.id)
             WHERE p.tenant_id = ? AND coalesce(v.stock_physical, 0) > coalesce(v.stock_reserved, 0)
               AND strpos(lower(concat_ws(' ', p.name, p.category, p.subcategory, v.size, v.color)), lower(?)) > 0
            """;
    private static final String SIZE = " AND (? = '' OR v.size = ?) ";
    private static final String COLOR = " AND (? = '' OR v.color = ?) ";
    private static final String CATEGORY = " AND (? = '' OR p.category = ?) ";

    private final JdbcTemplate db;

    public PublicCatalogRepository(JdbcTemplate db) {
        this.db = db;
    }

    public record StoreRow(String name, Pickup pickup) {}

    public Optional<StoreRow> store(UUID tenant) {
        return db.query("SELECT name, pickup_enabled, pickup_address, pickup_hours FROM tenant WHERE id=?",
                (rs, n) -> new StoreRow(rs.getString(1), rs.getBoolean(2) ? new Pickup(rs.getString(3), rs.getString(4)) : null),
                tenant).stream().findFirst();
    }

    public List<String> categories(UUID tenant, Filters f) {
        return db.queryForList("SELECT DISTINCT p.category " + MATCHES + SIZE + COLOR
                + " AND trim(p.category) <> '' ORDER BY p.category", String.class,
                tenant, f.query(), f.size(), f.size(), f.color(), f.color());
    }

    public List<String> sizes(UUID tenant, Filters f) {
        // Agrupado para poder ordenar las tallas de forma natural (XS, S, M, L…, luego 28, 30…).
        return db.queryForList("SELECT v.size " + MATCHES + CATEGORY + COLOR
                + " AND v.size <> '' GROUP BY v.size ORDER BY array_position(ARRAY['XXS','XS','S','M','L','XL','XXL','XXXL'], upper(trim(v.size))) NULLS LAST, length(v.size), v.size", String.class,
                tenant, f.query(), f.category(), f.category(), f.color(), f.color());
    }

    public List<String> colors(UUID tenant, Filters f) {
        return db.queryForList("SELECT DISTINCT v.color " + MATCHES + CATEGORY + SIZE
                + " AND trim(v.color) <> '' ORDER BY v.color", String.class,
                tenant, f.query(), f.category(), f.category(), f.size(), f.size());
    }

    /** Devuelve hasta {@code limit} variantes desde {@code offset}; la portada es la primera foto del producto. */
    public List<Variant> variants(UUID tenant, Filters f, int limit, long offset) {
        return db.query("""
                SELECT v.id, p.id, p.name, p.category, v.size, v.color, v.price, p.subcategory,
                       coalesce(v.stock_physical, 0) - coalesce(v.stock_reserved, 0) AS available,
                       (SELECT i.id FROM product_image i WHERE i.tenant_id = p.tenant_id AND i.product_id = p.id
                         ORDER BY i.position LIMIT 1) AS cover
                """ + MATCHES + CATEGORY + SIZE + COLOR + """
                   AND (CAST(? AS uuid) IS NULL OR v.id = ?)
                 ORDER BY p.name, p.id, array_position(ARRAY['XXS','XS','S','M','L','XL','XXL','XXXL'], upper(trim(v.size))) NULLS LAST, length(v.size), v.size, v.color, v.id LIMIT ? OFFSET ?
                """, (rs, n) -> new Variant(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getBigDecimal(7), rs.getInt("available"),
                rs.getObject("cover", UUID.class), rs.getString("subcategory")),
                tenant, f.query(), f.category(), f.category(), f.size(), f.size(), f.color(), f.color(),
                f.variant(), f.variant(), limit, offset);
    }

    public record CategoryCount(String category, String subcategory, int products) {}

    /**
     * Categorías y subcategorías con prendas en stock (subcategory null = productos sin subcategoría). "Polos" y "polos"
     * son la misma categoría: se agrupa sin distinguir mayúsculas y se muestra la forma más usada.
     */
    public List<CategoryCount> categoryTree(UUID tenant) {
        return db.query("""
                WITH s AS (
                    SELECT lower(trim(p.category)) AS ck, trim(p.category) AS c, lower(nullif(trim(p.subcategory), '')) AS sk,
                           nullif(trim(p.subcategory), '') AS sc, p.id
                      FROM product p JOIN product_variant v ON (v.tenant_id, v.product_id) = (p.tenant_id, p.id)
                     WHERE p.tenant_id = ? AND coalesce(v.stock_physical, 0) > coalesce(v.stock_reserved, 0)
                       AND nullif(trim(p.category), '') IS NOT NULL)
                SELECT n.name, mode() WITHIN GROUP (ORDER BY s.sc), count(DISTINCT s.id)
                  FROM s JOIN (SELECT ck, mode() WITHIN GROUP (ORDER BY c) AS name FROM s GROUP BY ck) n USING (ck)
                 GROUP BY s.ck, n.name, s.sk ORDER BY s.ck, s.sk NULLS LAST
                """, (rs, n) -> new CategoryCount(rs.getString(1), rs.getString(2), rs.getInt(3)), tenant);
    }

    public Optional<CategoryResource> resource(UUID tenant, String category) {
        // Recurso elegido por la tienda; en automático, PDF, luego imagen, luego enlace.
        return db.query("SELECT k.kind AS active, CASE k.kind WHEN 'LINK' THEN r.link_url WHEN 'PDF' THEN r.pdf_url"
                        + " WHEN 'IMAGE' THEN r.image_url END AS url FROM category_catalog_resource r"
                        + " CROSS JOIN LATERAL (SELECT " + CategoryResourceRepository.EFFECTIVE_KIND + " AS kind) k"
                        + " WHERE r.tenant_id = ? AND r.category = ? AND k.kind IS NOT NULL",
                (rs, n) -> new CategoryResource(rs.getString("active"), rs.getString("url")), tenant, category)
                .stream().findFirst();
    }

    public List<Availability> availability(UUID tenant, List<UUID> ids) {
        var params = new MapSqlParameterSource().addValue("store", tenant).addValue("ids", ids);
        return new NamedParameterJdbcTemplate(db).query("""
                SELECT id, price, greatest(0, coalesce(stock_physical, 0) - coalesce(stock_reserved, 0))
                  FROM product_variant WHERE tenant_id = :store AND id IN (:ids)
                """, params, (rs, n) -> new Availability(rs.getObject(1, UUID.class), rs.getBigDecimal(2), rs.getInt(3)));
    }
}
