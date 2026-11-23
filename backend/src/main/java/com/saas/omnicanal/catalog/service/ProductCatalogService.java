package com.saas.omnicanal.catalog.service;

import com.saas.omnicanal.catalog.model.CatalogRow;
import com.saas.omnicanal.catalog.model.VariantDraft;
import com.saas.omnicanal.catalog.repository.ProductRepository;
import com.saas.omnicanal.subscription.model.Entitlements;
import com.saas.omnicanal.subscription.service.EntitlementService;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProductCatalogService {
    public static final int PAGE_SIZE = 25;

    private final ProductRepository products;
    private final EntitlementService entitlements;

    public ProductCatalogService(ProductRepository products, EntitlementService entitlements) {
        this.products = products;
        this.entitlements = entitlements;
    }

    public record Page(List<CatalogRow> rows, boolean hasNext) {}

    public Page page(UUID store, int page, String query, boolean availableOnly) {
        if (page < 0 || query.length() > 150) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var rows = products.page(store, query.strip(), availableOnly, PAGE_SIZE + 1, (long) PAGE_SIZE * page);
        return new Page(rows.stream().limit(PAGE_SIZE).toList(), rows.size() > PAGE_SIZE);
    }

    public static VariantDraft normalize(String sku, String size, String color, java.math.BigDecimal price, int stock) {
        String normalized = sku.strip().toUpperCase(Locale.ROOT);
        if (normalized.length() > 50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return new VariantDraft(normalized, size.strip(), color.strip(), price, stock);
    }

    /** Alta de producto con sus variantes, respetando la cuota de productos del plan. */
    @Transactional
    public UUID create(UUID store, String name, String category, List<VariantDraft> variants) {
        products.lockTenantCatalog(store);
        entitlements.requireQuota(store, Entitlements.CATALOG_PRODUCTS, products.countProducts(store), 1);
        UUID id = UUID.randomUUID();
        products.insertProduct(store, id, name.strip(), category == null ? null : category.strip());
        try {
            for (VariantDraft v : variants) products.insertVariant(store, id, v);
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SKU repetido", ex);
        }
        return id;
    }

    @Transactional
    public void edit(UUID store, UUID variant, String name, String category, VariantDraft draft,
                     String productVersion, String variantVersion) {
        if (products.updateProductOfVariant(store, variant, name.strip(), category == null ? null : category.strip(),
                productVersion) == 0) throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED);
        try {
            if (products.updateVariant(store, variant, draft, variantVersion) == 0)
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED);
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SKU repetido", ex);
        }
    }
}
