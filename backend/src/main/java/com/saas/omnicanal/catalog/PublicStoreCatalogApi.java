package com.saas.omnicanal.catalog;

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
    record Catalog(String storeName, List<Variant> variants, boolean hasNext) {}

    @GetMapping("/api/public/stores/{store}/catalog")
    Catalog catalog(@PathVariable UUID store, @RequestParam(defaultValue = "") String query,
                    @RequestParam(defaultValue = "0") int page) {
        if (page < 0 || query.length() > 150) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String name = db.query("SELECT name FROM tenant WHERE id = ?", (rs, n) -> rs.getString(1), store)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var variants = db.query("""
                SELECT v.id, p.name, p.category, v.size, v.color, v.price
                  FROM product p JOIN product_variant v ON (v.tenant_id, v.product_id) = (p.tenant_id, p.id)
                 WHERE p.tenant_id = ? AND coalesce(v.stock_physical, 0) > coalesce(v.stock_reserved, 0)
                   AND strpos(lower(concat_ws(' ', p.name, p.category, v.size, v.color)), lower(?)) > 0
                 ORDER BY p.name, p.id, v.size, v.color, v.id LIMIT 25 OFFSET ?
                """, (rs, n) -> new Variant(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getBigDecimal(6)), store, query.strip(), page * 24L);
        return new Catalog(name, variants.stream().limit(24).toList(), variants.size() > 24);
    }
}
