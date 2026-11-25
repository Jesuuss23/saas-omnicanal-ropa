package com.saas.omnicanal.catalog.service;

import com.saas.omnicanal.catalog.model.CatalogRow;
import com.saas.omnicanal.catalog.model.ProductCard;
import com.saas.omnicanal.catalog.model.ProductDetails;
import com.saas.omnicanal.catalog.model.VariantDraft;
import com.saas.omnicanal.catalog.model.VariantLabel;
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

    public static final java.util.Set<String> STATUSES = java.util.Set.of("all", "active", "inactive", "available", "soldout", "low");

    public Page page(UUID store, int page, String query, boolean availableOnly) {
        return page(store, page, query, availableOnly ? "available" : "all", "");
    }

    /** Filtros rápidos: {@code status} es uno de {@link #STATUSES}; {@code category} vacía muestra todas. */
    public Page page(UUID store, int page, String query, String status, String category) {
        if (page < 0 || query.length() > 150 || category.length() > 50 || !STATUSES.contains(status))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var rows = products.page(store, query.strip(), status, category.strip(), PAGE_SIZE + 1, (long) PAGE_SIZE * page);
        return new Page(rows.stream().limit(PAGE_SIZE).toList(), rows.size() > PAGE_SIZE);
    }

    public static VariantDraft normalize(String sku, String size, String color, java.math.BigDecimal price, int stock) {
        return normalize(sku, size, color, price, stock, null, null);
    }

    /** El precio antes (tachado) debe ser mayor que el precio actual; el costo es opcional y nunca negativo. */
    public static VariantDraft normalize(String sku, String size, String color, java.math.BigDecimal price, int stock,
                                         java.math.BigDecimal compareAtPrice, java.math.BigDecimal cost) {
        String normalized = sku == null ? "" : sku.strip().toUpperCase(Locale.ROOT);
        if (normalized.length() > 50) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if (compareAtPrice != null && compareAtPrice.compareTo(price) <= 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El precio antes debe ser mayor que el precio actual");
        if (cost != null && cost.signum() < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El costo no puede ser negativo");
        return new VariantDraft(normalized, size.strip(), color.strip(), price, stock, compareAtPrice, cost);
    }

    public static ProductDetails details(String description, String material, Boolean active) {
        String d = blank(description), m = blank(material);
        if (d != null && d.length() > 2000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La descripción admite hasta 2000 caracteres");
        if (m != null && m.length() > 80) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return new ProductDetails(d, m, active == null || active);
    }

    /** Oculta o vuelve a mostrar un producto en la tienda pública, el PDF y el asistente. */
    public void setActive(UUID store, UUID product, boolean active) {
        if (products.setActive(store, product, active) == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    /** Alta de producto con sus variantes, respetando la cuota de productos del plan. */
    @Transactional
    public UUID create(UUID store, String name, String category, List<VariantDraft> variants) {
        return create(store, name, category, null, variants);
    }

    /** {@code subcategory} solo tiene sentido con categoría (por ejemplo Polos → Retro). */
    @Transactional
    public UUID create(UUID store, String name, String category, String subcategory, List<VariantDraft> variants) {
        return create(store, name, category, subcategory, ProductDetails.EMPTY, variants);
    }

    @Transactional
    public UUID create(UUID store, String name, String category, String subcategory, ProductDetails details,
                       List<VariantDraft> variants) {
        products.lockTenantCatalog(store);
        entitlements.requireQuota(store, Entitlements.CATALOG_PRODUCTS, products.countProducts(store), 1);
        UUID id = UUID.randomUUID();
        products.insertProduct(store, id, name.strip(), blank(category), blank(category) == null ? null : blank(subcategory), details);
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
    public void edit(UUID store, UUID variant, String name, String category, String subcategory, VariantDraft draft,
                     String productVersion, String variantVersion) {
        edit(store, variant, name, category, subcategory, null, draft, productVersion, variantVersion);
    }

    /** {@code details} nulo conserva descripción, material y estado actuales. */
    @Transactional
    public void edit(UUID store, UUID variant, String name, String category, String subcategory, ProductDetails details,
                     VariantDraft draft, String productVersion, String variantVersion) {
        if (draft.sku().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe el SKU");
        String cat = blank(category), sub = cat == null ? null : blank(subcategory);
        int updated = details == null
                ? products.updateProductOfVariant(store, variant, name.strip(), cat, sub, productVersion)
                : products.updateProductOfVariant(store, variant, name.strip(), cat, sub, details, productVersion);
        if (updated == 0) throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED);
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

    public java.util.Map<String, List<String>> subcategories(UUID store) {
        return products.subcategories(store);
    }

    /** Ficha del producto al que apunta un QR: el de una variante (etiquetas actuales) o el de un producto. */
    public ProductCard byCode(UUID store, UUID code) {
        return products.productForCode(store, code).flatMap(id -> products.cards(store, List.of(id)).stream().findFirst())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ese código no es de un producto de tu tienda"));
    }

    public List<VariantLabel> labels(UUID store, List<UUID> variants) {
        return products.labels(store, variants);
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
