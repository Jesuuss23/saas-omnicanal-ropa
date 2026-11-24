package com.saas.omnicanal.catalog.repository;

import com.saas.omnicanal.catalog.model.CatalogRow;
import com.saas.omnicanal.catalog.model.ProductCard;
import com.saas.omnicanal.catalog.model.VariantDraft;
import com.saas.omnicanal.catalog.model.VariantLabel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
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
                ORDER BY p.created_at DESC NULLS LAST, p.id, v.color,
                         array_position(ARRAY['XXS','XS','S','M','L','XL','XXL','XXXL'], upper(trim(v.size))) NULLS LAST,
                         length(v.size), v.size, v.sku, v.id LIMIT ? OFFSET ?
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

    /** Categorías ya usadas en la tienda, para ofrecerlas en un select al crear o editar productos. */
    public List<String> categories(UUID tenant) {
        return db.queryForList("""
                SELECT DISTINCT trim(category) FROM product
                WHERE tenant_id = ? AND nullif(trim(category), '') IS NOT NULL ORDER BY 1
                """, String.class, tenant);
    }

    public boolean skuTaken(UUID tenant, String sku) {
        return Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM product_variant WHERE tenant_id = ? AND upper(trim(sku)) = upper(trim(?)))",
                Boolean.class, tenant, sku));
    }

    /** Resuelve el contenido de un QR: el id de una variante (etiqueta por talla y color) o el de un producto. */
    public Optional<UUID> productForCode(UUID tenant, UUID code) {
        return db.queryForList("""
                SELECT id FROM product WHERE tenant_id = ? AND id = ?
                UNION ALL SELECT product_id FROM product_variant WHERE tenant_id = ? AND id = ?
                """, UUID.class, tenant, code, tenant, code).stream().findFirst();
    }

    /** Productos cuyo nombre, categoría, SKU, talla o color contienen el texto (máximo {@code limit}). */
    public List<UUID> searchProducts(UUID tenant, String query, int limit) {
        return db.queryForList("""
                SELECT p.id FROM product p
                WHERE p.tenant_id = ? AND EXISTS (
                    SELECT 1 FROM product_variant v WHERE (v.tenant_id, v.product_id) = (p.tenant_id, p.id)
                      AND strpos(lower(concat_ws(' ', p.name, p.category, v.sku, v.size, v.color)), lower(?)) > 0)
                ORDER BY lower(p.name), p.id LIMIT ?
                """, UUID.class, tenant, query, limit);
    }

    /** Fichas de producto con sus variantes, en el orden de {@code ids}. Omite los que no son de la tienda. */
    public List<ProductCard> cards(UUID tenant, List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        var found = new LinkedHashMap<UUID, ProductCard>();
        String[] array = ids.stream().map(UUID::toString).toArray(String[]::new);
        db.query("""
                SELECT p.id AS product_id, p.name, p.category,
                       (SELECT i.id FROM product_image i WHERE i.tenant_id = p.tenant_id AND i.product_id = p.id
                         ORDER BY i.position LIMIT 1) AS cover_image,
                       v.id, v.sku, v.size, v.color, v.price,
                       coalesce(v.stock_physical, 0) - coalesce(v.stock_reserved, 0) AS available
                FROM product p JOIN product_variant v ON (v.tenant_id, v.product_id) = (p.tenant_id, p.id)
                WHERE p.tenant_id = ? AND p.id = ANY (CAST(? AS uuid[]))
                ORDER BY p.id, v.color,
                         array_position(ARRAY['XXS','XS','S','M','L','XL','XXL','XXXL'], upper(trim(v.size))) NULLS LAST,
                         length(v.size), v.size, v.sku
                """, rs -> {
            UUID product = rs.getObject("product_id", UUID.class);
            found.computeIfAbsent(product, id -> {
                try {
                    return new ProductCard(id, rs.getString("name"), rs.getString("category"),
                            rs.getObject("cover_image", UUID.class), new ArrayList<>());
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            }).variants().add(new ProductCard.Option(rs.getObject("id", UUID.class), rs.getString("sku"),
                    rs.getString("size"), rs.getString("color"), rs.getBigDecimal("price"), rs.getInt("available")));
        }, tenant, array);
        return ids.stream().distinct().map(found::get).filter(java.util.Objects::nonNull).toList();
    }

    /** Variantes para imprimir etiquetas, en el orden de {@code ids}. Omite las que no son de la tienda. */
    public List<VariantLabel> labels(UUID tenant, List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        var found = new java.util.HashMap<UUID, VariantLabel>();
        db.query("""
                SELECT v.id, p.name, v.sku, v.size, v.color, v.price
                FROM product_variant v JOIN product p ON (p.tenant_id, p.id) = (v.tenant_id, v.product_id)
                WHERE v.tenant_id = ? AND v.id = ANY (CAST(? AS uuid[]))
                """, rs -> {
            UUID id = rs.getObject("id", UUID.class);
            found.put(id, new VariantLabel(id, rs.getString("name"), rs.getString("sku"), rs.getString("size"),
                    rs.getString("color"), rs.getBigDecimal("price")));
        }, tenant, ids.stream().map(UUID::toString).toArray(String[]::new));
        return ids.stream().distinct().map(found::get).filter(java.util.Objects::nonNull).toList();
    }
}
