package com.saas.omnicanal.pos.controller;

import com.saas.omnicanal.catalog.model.ProductCard;
import com.saas.omnicanal.catalog.service.ProductCatalogService;
import java.math.BigDecimal;
import java.util.List;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/owner/stores/{store}/pos")
class PosVariantController {
    private final JdbcTemplate db;
    private final ProductCatalogService catalog;

    PosVariantController(JdbcTemplate db, ProductCatalogService catalog) {
        this.db = db;
        this.catalog = catalog;
    }

    /** Buscador de caja: productos con sus variantes por nombre, categoría, SKU, talla o color. */
    @GetMapping("/products")
    List<ProductCard> search(@PathVariable UUID store, Principal owner, @RequestParam(defaultValue = "") String query) {
        if (!store.toString().equals(owner.getName())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return catalog.search(store, query, 20);
    }

    /** Lo que leyó la cámara: el QR de un producto (o el de una variante, en etiquetas antiguas). */
    @GetMapping("/products/{code}")
    ProductCard byCode(@PathVariable UUID store, Principal owner, @PathVariable UUID code) {
        if (!store.toString().equals(owner.getName())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return catalog.byCode(store, code);
    }

    record Item(UUID id, String name, String sku, String size, String color, BigDecimal price, int available) {}

    @GetMapping("/variant")
    Item lookup(@PathVariable UUID store, Principal owner, @RequestParam(required = false) UUID id,
                @RequestParam(required = false) String sku) {
        if (!store.toString().equals(owner.getName())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if ((id == null) == (sku == null) || (sku != null && (sku.isBlank() || sku.length() > 50))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        String filter = id == null ? "upper(trim(v.sku)) = upper(trim(?))" : "v.id = ?";
        return db.query("""
                SELECT v.id, p.name, v.sku, v.size, v.color, v.price,
                       coalesce(v.stock_physical, 0) - coalesce(v.stock_reserved, 0) AS available
                FROM product_variant v JOIN product p ON p.id = v.product_id AND p.tenant_id = v.tenant_id
                WHERE v.tenant_id = ? AND
                """ + filter, (rs, row) -> new Item(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("sku"), rs.getString("size"), rs.getString("color"), rs.getBigDecimal("price"),
                rs.getInt("available")), store, id == null ? sku : id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
