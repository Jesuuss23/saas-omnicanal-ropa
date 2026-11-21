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
    record Row(UUID id, String name, String sku, String size, String color, BigDecimal price, int available,
               String category, int physical, int reserved, String productVersion, String variantVersion) {}
    record Page(List<Row> rows, boolean hasNext) {}
    record Created(UUID id) {}

    private void requireStore(UUID store, Principal owner) {
        if (!store.toString().equals(owner.getName())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    @GetMapping
    Page list(@PathVariable UUID store, Principal principal, @RequestParam(defaultValue="0") int page,
              @RequestParam(defaultValue="") String query, @RequestParam(defaultValue="false") boolean availableOnly) {
        requireStore(store, principal);
        if (page < 0 || query.length() > 150) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var rows = db.query("""
                SELECT v.id, p.name, v.sku, v.size, v.color, v.price,
                       coalesce(v.stock_physical, 0) - coalesce(v.stock_reserved, 0) AS available,
                       p.category, coalesce(v.stock_physical, 0) AS physical, coalesce(v.stock_reserved, 0) AS reserved,
                       p.xmin::text AS product_version, v.xmin::text AS variant_version
                FROM product_variant v JOIN product p ON (p.tenant_id, p.id) = (v.tenant_id, v.product_id)
                WHERE v.tenant_id = ?
                  AND (NOT ? OR coalesce(v.stock_physical,0) > coalesce(v.stock_reserved,0))
                  AND strpos(lower(concat_ws(' ',p.name,p.category,v.sku,v.size,v.color)),lower(?)) > 0
                ORDER BY p.created_at DESC NULLS LAST, p.id, v.sku, v.id LIMIT 26 OFFSET ?
                """, (rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("sku"),
                rs.getString("size"), rs.getString("color"), rs.getBigDecimal("price"), rs.getInt("available"),
                rs.getString("category"), rs.getInt("physical"), rs.getInt("reserved"),
                rs.getString("product_version"), rs.getString("variant_version")),
                store, availableOnly, query.strip(), 25L * page);
        return new Page(rows.stream().limit(25).toList(), rows.size() > 25);
    }

    record Edit(@NotBlank @Size(max=150) String name, @Size(max=50) String category,
                @NotNull @Valid Variant variant, @NotBlank String productVersion, @NotBlank String variantVersion) {}

    @PutMapping("/variants/{id}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void edit(@PathVariable UUID store, @PathVariable UUID id, Principal principal, @Valid @RequestBody Edit edit) {
        requireStore(store, principal);
        Variant variant = edit.variant();
        String sku = variant.sku().strip().toUpperCase(Locale.ROOT);
        if (sku.length() > 50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        int productRows = db.update("""
                UPDATE product p SET name = ?, category = ?
                FROM product_variant v
                WHERE p.tenant_id = ? AND v.tenant_id = p.tenant_id AND v.product_id = p.id
                  AND v.id = ? AND p.xmin::text = ?
                """, edit.name().strip(), edit.category() == null ? null : edit.category().strip(),
                store, id, edit.productVersion());
        if (productRows == 0) throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED);
        try {
            int variantRows = db.update("""
                    UPDATE product_variant SET sku = ?, size = ?, color = ?, price = ?, stock_physical = ?
                    WHERE tenant_id = ? AND id = ? AND xmin::text = ? AND coalesce(stock_reserved, 0) <= ?
                    """, sku, variant.size().strip(), variant.color().strip(), variant.price(), variant.stock(),
                    store, id, edit.variantVersion(), variant.stock());
            if (variantRows == 0) throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED);
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SKU repetido", ex);
        }
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
