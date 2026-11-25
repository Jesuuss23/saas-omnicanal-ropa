package com.saas.omnicanal.catalog.controller;

import static com.saas.omnicanal.shared.security.OwnerAccess.requireOwner;

import com.saas.omnicanal.catalog.model.VariantDraft;
import com.saas.omnicanal.catalog.service.ProductCatalogService;
import com.saas.omnicanal.catalog.service.ProductDescriptionService;
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
    private final ProductDescriptionService descriptions;

    OwnerCatalogController(ProductCatalogService catalog, ProductDescriptionService descriptions) {
        this.catalog = catalog;
        this.descriptions = descriptions;
    }

    record DescriptionRequest(@NotBlank @Size(max=150) String name, @Size(max=50) String category, @Size(max=50) String subcategory,
                              @Size(max=80) String material, @Size(max=30) List<@Size(max=30) String> colors,
                              @Size(max=30) List<@Size(max=10) String> sizes, @Size(max=500) String notes) {}

    /** Redacta la descripción con IA a partir de los datos del formulario (sirve también antes de guardar el producto). */
    @PostMapping("/description/generate")
    ProductDescriptionService.Result generateDescription(@PathVariable UUID store, Principal principal,
                                                         @Valid @RequestBody DescriptionRequest r) {
        requireOwner(store, principal);
        return descriptions.generate(new ProductDescriptionService.Facts(r.name(), r.category(), r.subcategory(), r.material(),
                r.colors(), r.sizes(), r.notes()));
    }

    /** El SKU puede venir vacío al crear: se genera con nombre, color y talla. */
    record Variant(@Size(max=50) String sku, @NotBlank @Size(max=10) String size,
                   @NotBlank @Size(max=30) String color,
                   @NotNull @Positive @Digits(integer=8, fraction=2) BigDecimal price,
                   @NotNull @PositiveOrZero Integer stock,
                   @Positive @Digits(integer=8, fraction=2) BigDecimal compareAtPrice,
                   @PositiveOrZero @Digits(integer=8, fraction=2) BigDecimal cost) {
        VariantDraft draft() {
            return ProductCatalogService.normalize(sku, size, color, price, stock, compareAtPrice, cost);
        }
    }
    record Product(@NotBlank @Size(max=150) String name, @Size(max=50) String category, @Size(max=50) String subcategory,
                   @Size(max=2000) String description, @Size(max=80) String material, Boolean active,
                   @NotEmpty @Size(max=100) List<@NotNull @Valid Variant> variants) {}
    record Created(UUID id) {}
    /** Sin {@code active} (clientes antiguos) se conservan descripción, material y estado. */
    record Edit(@NotBlank @Size(max=150) String name, @Size(max=50) String category, @Size(max=50) String subcategory,
                @Size(max=2000) String description, @Size(max=80) String material, Boolean active,
                @NotNull @Valid Variant variant, @NotBlank String productVersion, @NotBlank String variantVersion) {}
    record Active(@NotNull Boolean active) {}

    @GetMapping
    ProductCatalogService.Page list(@PathVariable UUID store, Principal principal, @RequestParam(defaultValue="0") int page,
                                    @RequestParam(defaultValue="") String query,
                                    @RequestParam(defaultValue="false") boolean availableOnly,
                                    @RequestParam(defaultValue="all") String status,
                                    @RequestParam(defaultValue="") String category) {
        requireOwner(store, principal);
        return catalog.page(store, page, query, availableOnly && "all".equals(status) ? "available" : status, category);
    }

    @GetMapping("/categories")
    List<String> categories(@PathVariable UUID store, Principal principal) {
        requireOwner(store, principal);
        return catalog.categories(store);
    }

    /** Subcategorías por categoría, por ejemplo {"Polos": ["Retro", "Verano"]}. */
    @GetMapping("/subcategories")
    java.util.Map<String, List<String>> subcategories(@PathVariable UUID store, Principal principal) {
        requireOwner(store, principal);
        return catalog.subcategories(store);
    }

    @PutMapping("/variants/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void edit(@PathVariable UUID store, @PathVariable UUID id, Principal principal, @Valid @RequestBody Edit edit) {
        requireOwner(store, principal);
        var details = edit.active() == null ? null
                : ProductCatalogService.details(edit.description(), edit.material(), edit.active());
        catalog.edit(store, id, edit.name(), edit.category(), edit.subcategory(), details, edit.variant().draft(),
                edit.productVersion(), edit.variantVersion());
    }

    @PutMapping("/products/{productId}/active")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void active(@PathVariable UUID store, @PathVariable UUID productId, Principal principal, @Valid @RequestBody Active body) {
        requireOwner(store, principal);
        catalog.setActive(store, productId, body.active());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Created create(@PathVariable UUID store, Principal principal, @Valid @RequestBody Product product) {
        requireOwner(store, principal);
        var drafts = product.variants().stream().map(Variant::draft).toList();
        return new Created(catalog.create(store, product.name(), product.category(), product.subcategory(),
                ProductCatalogService.details(product.description(), product.material(), product.active()), drafts));
    }
}
