package com.saas.omnicanal.catalog.service;

import com.saas.omnicanal.catalog.model.CatalogRow;
import com.saas.omnicanal.catalog.model.ProductCard;
import com.saas.omnicanal.catalog.model.VariantDraft;
import com.saas.omnicanal.catalog.repository.ProductRepository;
import com.saas.omnicanal.subscription.model.Entitlements;
import com.saas.omnicanal.subscription.service.EntitlementService;
import java.text.Normalizer;
import java.util.HashSet;
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
        String normalized = sku == null ? "" : sku.strip().toUpperCase(Locale.ROOT);
        if (normalized.length() > 50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return new VariantDraft(normalized, size.strip(), color.strip(), price, stock);
    }

    /** Alta de producto con sus variantes, respetando la cuota de productos del plan. */
    @Transactional
    public UUID create(UUID store, String name, String category, List<VariantDraft> variants) {
        products.lockTenantCatalog(store);
        entitlements.requireQuota(store, Entitlements.CATALOG_PRODUCTS, products.countProducts(store), 1);
        UUID id = UUID.randomUUID();
        products.insertProduct(store, id, name.strip(), blank(category));
        var used = new HashSet<String>();
        try {
            for (VariantDraft v : variants) {
                VariantDraft draft = v.sku().isEmpty() ? v.withSku(freeSku(store, skuBase(name, v.color(), v.size()), used)) : v;
                if (!used.add(draft.sku())) throw new ResponseStatusException(HttpStatus.CONFLICT, "SKU repetido: " + draft.sku());
                products.insertVariant(store, id, draft);
            }
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ese SKU ya existe en tu tienda", ex);
        }
        return id;
    }

    @Transactional
    public void edit(UUID store, UUID variant, String name, String category, VariantDraft draft,
                     String productVersion, String variantVersion) {
        if (draft.sku().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe el SKU");
        if (products.updateProductOfVariant(store, variant, name.strip(), blank(category),
                productVersion) == 0) throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED);
        try {
            if (products.updateVariant(store, variant, draft, variantVersion) == 0)
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED);
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ese SKU ya existe en tu tienda", ex);
        }
    }

    public List<String> categories(UUID store) {
        return products.categories(store);
    }

    /** Ficha del producto al que apunta un QR (producto o, en etiquetas antiguas, variante). */
    public ProductCard byCode(UUID store, UUID code) {
        return products.productForCode(store, code).flatMap(id -> products.cards(store, List.of(id)).stream().findFirst())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ese código no es de un producto de tu tienda"));
    }

    public List<ProductCard> cards(UUID store, List<UUID> ids) {
        return products.cards(store, ids);
    }

    public List<ProductCard> search(UUID store, String query, int limit) {
        String q = query == null ? "" : query.strip();
        if (q.length() > 150) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return products.cards(store, products.searchProducts(store, q, limit));
    }

    /** SKU legible: NOMBRE-COLOR-TALLA sin tildes, p. ej. "Polo básico", "Negro", "M" da POLO-BASICO-NEGRO-M. */
    public static String skuBase(String name, String color, String size) {
        String n = slug(name), c = slug(color), z = slug(size);
        int room = 50 - 6 - (c.isEmpty() ? 0 : c.length() + 1) - (z.isEmpty() ? 0 : z.length() + 1);
        if (n.length() > Math.max(room, 8)) n = n.substring(0, Math.max(room, 8)).replaceAll("-+$", "");
        String sku = String.join("-", java.util.stream.Stream.of(n, c, z).filter(t -> !t.isEmpty()).toList());
        if (sku.length() > 44) sku = sku.substring(0, 44).replaceAll("-+$", "");
        return sku.isEmpty() ? "PRODUCTO" : sku;
    }

    static String slug(String text) {
        if (text == null) return "";
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return plain.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private String freeSku(UUID store, String base, java.util.Set<String> used) {
        for (int n = 1; n < 1000; n++) {
            String candidate = n == 1 ? base : base + "-" + n;
            if (!used.contains(candidate) && !products.skuTaken(store, candidate)) return candidate;
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "No se pudo generar un SKU libre; escríbelo a mano");
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }
}
