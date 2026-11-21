package com.saas.omnicanal.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/owner/stores/{store}/catalog")
public class OwnerCatalogApi {
    private final JdbcTemplate db;

    OwnerCatalogApi(JdbcTemplate db) {
        this.db = db;
    }

    record Variant(@NotBlank @Size(max=50) String sku, @NotBlank @Size(max=10) String size,
                   @NotBlank @Size(max=30) String color,
                   @NotNull @Positive @Digits(integer=8, fraction=2) BigDecimal price,
                   @NotNull @PositiveOrZero Integer stock) {}
    record Product(@NotBlank @Size(max=150) String name, @Size(max=50) String category,
                   @NotEmpty @Size(max=100) List<@NotNull @Valid Variant> variants) {}
    record Row(UUID id, String name, String sku, String size, String color, BigDecimal price, int available) {}
    record Page(List<Row> rows, boolean hasNext) {}
    record Created(UUID id) {}

    private void requireStore(UUID store, Principal owner) {
        if (!store.toString().equals(owner.getName())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    @GetMapping
    Page list(@PathVariable UUID store, Principal principal, @RequestParam(defaultValue="0") int page) {
        requireStore(store, principal);
        if (page < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var rows = db.query("""
                SELECT v.id, p.name, v.sku, v.size, v.color, v.price,
                       coalesce(v.stock_physical, 0) - coalesce(v.stock_reserved, 0) AS available
                FROM product_variant v JOIN product p ON (p.tenant_id, p.id) = (v.tenant_id, v.product_id)
                WHERE v.tenant_id = ? ORDER BY p.created_at DESC NULLS LAST, p.id, v.sku, v.id LIMIT 26 OFFSET ?
                """, (rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("sku"),
                rs.getString("size"), rs.getString("color"), rs.getBigDecimal("price"), rs.getInt("available")),
                store, 25L * page);
        return new Page(rows.stream().limit(25).toList(), rows.size() > 25);
    }

    @PostMapping
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    Created create(@PathVariable UUID store, Principal principal, @Valid @RequestBody Product product) {
        requireStore(store, principal);
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO product(id, tenant_id, name, category) VALUES (?, ?, ?, ?)",
                id, store, product.name().strip(), product.category() == null ? null : product.category().strip());
        try {
            for (Variant variant : product.variants()) {
                db.update("""
                        INSERT INTO product_variant(product_id, tenant_id, sku, size, color, price, stock_physical)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, id, store, variant.sku().strip().toUpperCase(Locale.ROOT), variant.size().strip(),
                        variant.color().strip(), variant.price(), variant.stock());
            }
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SKU repetido", ex);
        }
        return new Created(id);
    }
}
