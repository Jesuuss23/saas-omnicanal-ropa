package com.saas.omnicanal.catalog.controller;

import static com.saas.omnicanal.shared.security.OwnerAccess.requireOwner;

import com.saas.omnicanal.catalog.service.ProductCatalogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/owner/stores/{store}/catalog")
public class OwnerCatalogController {
    private final ProductCatalogService catalog;

    OwnerCatalogController(ProductCatalogService catalog) {
        this.catalog = catalog;
    }

    /** El SKU puede venir vacío al crear: se genera con nombre, color y talla. */
    record Variant(@Size(max=50) String sku, @NotBlank @Size(max=10) String size,
                   @NotBlank @Size(max=30) String color,
                   @NotNull @Positive @Digits(integer=8, fraction=2) BigDecimal price,
                   @NotNull @PositiveOrZero Integer stock) {}
    record Product(@NotBlank @Size(max=150) String name, @Size(max=50) String category,
                   @NotEmpty @Size(max=100) List<@NotNull @Valid Variant> variants) {}
    record Created(UUID id) {}
    record Edit(@NotBlank @Size(max=150) String name, @Size(max=50) String category,
                @NotNull @Valid Variant variant, @NotBlank String productVersion, @NotBlank String variantVersion) {}

    @GetMapping
    ProductCatalogService.Page list(@PathVariable UUID store, Principal principal, @RequestParam(defaultValue="0") int page,
                                    @RequestParam(defaultValue="") String query,
                                    @RequestParam(defaultValue="false") boolean availableOnly) {
        requireOwner(store, principal);
        return catalog.page(store, page, query, availableOnly);
    }

    @GetMapping("/categories")
    List<String> categories(@PathVariable UUID store, Principal principal) {
        requireOwner(store, principal);
        return catalog.categories(store);
    }

    @PutMapping("/variants/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void edit(@PathVariable UUID store, @PathVariable UUID id, Principal principal, @Valid @RequestBody Edit edit) {
        requireOwner(store, principal);
        Variant v = edit.variant();
        catalog.edit(store, id, edit.name(), edit.category(),
                ProductCatalogService.normalize(v.sku(), v.size(), v.color(), v.price(), v.stock()),
                edit.productVersion(), edit.variantVersion());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Created create(@PathVariable UUID store, Principal principal, @Valid @RequestBody Product product) {
        requireOwner(store, principal);
        var drafts = product.variants().stream()
                .map(v -> ProductCatalogService.normalize(v.sku(), v.size(), v.color(), v.price(), v.stock())).toList();
        return new Created(catalog.create(store, product.name(), product.category(), drafts));
    }
}
