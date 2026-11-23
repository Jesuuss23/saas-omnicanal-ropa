package com.saas.omnicanal.catalog.repository;

import com.saas.omnicanal.catalog.model.CatalogRow;
import com.saas.omnicanal.catalog.model.VariantDraft;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Productos y variantes, siempre filtrados por tenant_id. */
@Repository
public class ProductRepository {
    private final JdbcTemplate db;

    public ProductRepository(JdbcTemplate db) {
        this.db = db;
    }

    public List<CatalogRow> page(UUID tenant, String query, boolean availableOnly, int limit, long offset) {
        return db.query("""
                SELECT v.id, p.name, v.sku, v.size, v.color, v.price,
                       coalesce(v.stock_physical, 0) - coalesce(v.stock_reserved, 0) AS available,
                       p.category, coalesce(v.stock_physical, 0) AS physical, coalesce(v.stock_reserved, 0) AS reserved,
                       p.xmin::text AS product_version, v.xmin::text AS variant_version, p.id AS product_id,
                       (SELECT i.id FROM product_image i WHERE i.tenant_id = p.tenant_id AND i.product_id = p.id
                         ORDER BY i.position LIMIT 1) AS cover_image
                FROM product_variant v JOIN product p ON (p.tenant_id, p.id) = (v.tenant_id, v.product_id)
                WHERE v.tenant_id = ?
                  AND (NOT ? OR coalesce(v.stock_physical,0) > coalesce(v.stock_reserved,0))
                  AND strpos(lower(concat_ws(' ',p.name,p.category,v.sku,v.size,v.color)),lower(?)) > 0
                ORDER BY p.created_at DESC NULLS LAST, p.id, v.sku, v.id LIMIT ? OFFSET ?
                """, (rs, n) -> new CatalogRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("sku"),
                rs.getString("size"), rs.getString("color"), rs.getBigDecimal("price"), rs.getInt("available"),
                rs.getString("category"), rs.getInt("physical"), rs.getInt("reserved"),
                rs.getString("product_version"), rs.getString("variant_version"),
                rs.getObject("product_id", UUID.class), rs.getObject("cover_image", UUID.class)),
                tenant, availableOnly, query, limit, offset);
    }

    public long countProducts(UUID tenant) {
        Long count = db.queryForObject("SELECT count(*) FROM product WHERE tenant_id=?", Long.class, tenant);
        return count == null ? 0 : count;
    }

    /** Serializa altas concurrentes de productos de la misma tienda para respetar la cuota del plan. */
    public void lockTenantCatalog(UUID tenant) {
        db.queryForObject("SELECT id FROM tenant WHERE id=? FOR UPDATE", UUID.class, tenant);
    }

    public void insertProduct(UUID tenant, UUID id, String name, String category) {
        db.update("INSERT INTO product(id, tenant_id, name, category) VALUES (?, ?, ?, ?)", id, tenant, name, category);
    }

    public void insertVariant(UUID tenant, UUID product, VariantDraft v) {
        db.update("""
                INSERT INTO product_variant(product_id, tenant_id, sku, size, color, price, stock_physical)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, product, tenant, v.sku(), v.size(), v.color(), v.price(), v.stock());
    }

    public int updateProductOfVariant(UUID tenant, UUID variant, String name, String category, String productVersion) {
        return db.update("""
                UPDATE product p SET name = ?, category = ?
                FROM product_variant v
                WHERE p.tenant_id = ? AND v.tenant_id = p.tenant_id AND v.product_id = p.id
                  AND v.id = ? AND p.xmin::text = ?
                """, name, category, tenant, variant, productVersion);
    }

    /** No permite bajar el stock físico por debajo de lo ya reservado. */
    public int updateVariant(UUID tenant, UUID variant, VariantDraft v, String variantVersion) {
        return db.update("""
                UPDATE product_variant SET sku = ?, size = ?, color = ?, price = ?, stock_physical = ?
                WHERE tenant_id = ? AND id = ? AND xmin::text = ? AND coalesce(stock_reserved, 0) <= ?
                """, v.sku(), v.size(), v.color(), v.price(), v.stock(), tenant, variant, variantVersion, v.stock());
    }
}
