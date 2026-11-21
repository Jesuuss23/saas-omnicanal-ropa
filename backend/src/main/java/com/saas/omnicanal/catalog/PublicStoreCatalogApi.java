package com.saas.omnicanal.catalog;

import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
class PublicStoreCatalogApi {
    private final JdbcTemplate db;

    PublicStoreCatalogApi(JdbcTemplate db) {
        this.db = db;
    }

    record Variant(UUID id, String name, String category, String size, String color, BigDecimal price) {}
    record Pickup(String address, String hours) {}
    record Store(String name, Pickup pickup) {}
    record CategoryResource(String kind, String url) {}
    record Catalog(String storeName, List<Variant> variants, boolean hasNext, Pickup pickup, List<String> sizes, List<String> colors, List<String> categories, CategoryResource resource) {}

    record Availability(UUID id, BigDecimal price, int available) {}

    @GetMapping("/api/public/stores/{store}/availability")
    ResponseEntity<List<Availability>> availability(@PathVariable UUID store,
            @RequestParam List<UUID> ids) {
        if (ids.isEmpty() || ids.size() > 24) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var params = new MapSqlParameterSource()
                .addValue("store", store).addValue("ids", ids);
        var jdbc = new NamedParameterJdbcTemplate(db);
        var rows = jdbc.query("""
                SELECT id, price, greatest(0, coalesce(stock_physical, 0) - coalesce(stock_reserved, 0))
                  FROM product_variant WHERE tenant_id = :store AND id IN (:ids)
                """, params, (rs, n) -> new Availability(rs.getObject(1, UUID.class), rs.getBigDecimal(2), rs.getInt(3)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rows);
    }

    @GetMapping("/api/public/stores/{store}/catalog")
    Catalog catalog(@PathVariable UUID store, @RequestParam(defaultValue = "") String query,
                    @RequestParam(defaultValue = "0") int page,
                    @RequestParam(defaultValue = "") String size,
                    @RequestParam(defaultValue = "") String color,
                    @RequestParam(defaultValue = "") String category) {
        if (page < 0 || query.length() > 150) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Store details = db.query("""
                SELECT name,pickup_enabled,pickup_address,pickup_hours FROM tenant WHERE id=?
                """,(rs,n)->new Store(rs.getString(1),rs.getBoolean(2)
                    ? new Pickup(rs.getString(3),rs.getString(4)) : null),store)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String matches = """
                  FROM product p JOIN product_variant v ON (v.tenant_id, v.product_id) = (p.tenant_id, p.id)
                 WHERE p.tenant_id = ? AND coalesce(v.stock_physical, 0) > coalesce(v.stock_reserved, 0)
                   AND strpos(lower(concat_ws(' ', p.name, p.category, v.size, v.color)), lower(?)) > 0
                """;
        String search = query.strip();
        String sizeFilter = " AND (? = '' OR v.size = ?) ";
        String colorFilter = " AND (? = '' OR v.color = ?) ";
        String categoryFilter = " AND (? = '' OR p.category = ?) ";
        var categories = db.queryForList("SELECT DISTINCT p.category " + matches + sizeFilter + colorFilter
                + " AND trim(p.category) <> '' ORDER BY p.category", String.class, store, search, size, size, color, color);
        var sizes = db.queryForList("SELECT DISTINCT v.size " + matches + categoryFilter + colorFilter
                + " AND v.size <> '' ORDER BY v.size", String.class, store, search, category, category, color, color);
        var colors = db.queryForList("SELECT DISTINCT v.color " + matches + categoryFilter + sizeFilter
                + " AND trim(v.color) <> '' ORDER BY v.color", String.class, store, search, category, category, size, size);
        var variants = db.query("SELECT v.id, p.name, p.category, v.size, v.color, v.price " + matches + categoryFilter + sizeFilter + colorFilter + """
                 ORDER BY p.name, p.id, v.size, v.color, v.id LIMIT 25 OFFSET ?
                """, (rs, n) -> new Variant(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getBigDecimal(6)), store, search, category, category, size, size, color, color, page * 24L);
        CategoryResource resource = category.isBlank() ? null : db.query("""
                SELECT active, CASE active WHEN 'LINK' THEN link_url WHEN 'PDF' THEN pdf_url
                                           WHEN 'IMAGE' THEN image_url END AS url
                  FROM category_catalog_resource
                 WHERE tenant_id = ? AND category = ? AND active IN ('LINK','PDF','IMAGE')
                """, (rs,n) -> new CategoryResource(rs.getString("active"),rs.getString("url")),store,category)
                .stream().findFirst().orElse(null);
        return new Catalog(details.name(), variants.stream().limit(24).toList(), variants.size() > 24, details.pickup(), sizes, colors, categories, resource);
    }
}
