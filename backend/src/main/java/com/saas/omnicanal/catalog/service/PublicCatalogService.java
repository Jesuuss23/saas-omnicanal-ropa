package com.saas.omnicanal.catalog.service;

import com.saas.omnicanal.catalog.model.PublicCatalog;
import com.saas.omnicanal.catalog.model.PublicCatalog.Availability;
import com.saas.omnicanal.catalog.model.PublicCatalog.Brand;
import com.saas.omnicanal.catalog.model.PublicCatalog.Filters;
import com.saas.omnicanal.catalog.repository.PublicCatalogRepository;
import com.saas.omnicanal.tenant.repository.AppearanceRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PublicCatalogService {
    public static final int PAGE_SIZE = 24;

    private final PublicCatalogRepository catalog;
    private final AppearanceRepository appearance;

    public PublicCatalogService(PublicCatalogRepository catalog, AppearanceRepository appearance) {
        this.catalog = catalog;
        this.appearance = appearance;
    }

    @Transactional(readOnly = true)
    public PublicCatalog catalog(UUID tenant, Filters raw, int page) {
        if (page < 0 || raw.query().length() > 150) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var store = catalog.store(tenant).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var f = new Filters(raw.query().strip(), raw.size(), raw.color(), raw.category(), raw.variant());
        var rows = catalog.variants(tenant, f, PAGE_SIZE + 1, (long) page * PAGE_SIZE);
        var resource = f.category().isBlank() ? null : catalog.resource(tenant, f.category()).orElse(null);
        var brand = appearance.find(tenant).map(a -> new Brand(a.tagline(), a.description(), a.primaryColor(),
                a.whatsappPhone(), a.instagram(), a.hasLogo() ? a.logoVersion() : null, a.logoUrl())).orElse(null);
        return new PublicCatalog(store.name(), rows.stream().limit(PAGE_SIZE).toList(), rows.size() > PAGE_SIZE,
                store.pickup(), catalog.sizes(tenant, f), catalog.colors(tenant, f), catalog.categories(tenant, f),
                resource, brand);
    }

    @Transactional(readOnly = true)
    public PublicCatalog.ProductDetail product(UUID tenant, UUID product) {
        return catalog.product(tenant, product).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<Availability> availability(UUID tenant, List<UUID> ids) {
        if (ids.isEmpty() || ids.size() > PAGE_SIZE) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return catalog.availability(tenant, ids);
    }
}
