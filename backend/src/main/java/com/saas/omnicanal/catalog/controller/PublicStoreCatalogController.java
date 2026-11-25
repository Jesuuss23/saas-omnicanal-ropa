package com.saas.omnicanal.catalog.controller;

import com.saas.omnicanal.catalog.model.PublicCatalog;
import com.saas.omnicanal.catalog.model.PublicCatalog.Availability;
import com.saas.omnicanal.catalog.model.PublicCatalog.Filters;
import com.saas.omnicanal.catalog.service.PublicCatalogService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
class PublicStoreCatalogController {
    private final PublicCatalogService catalog;

    PublicStoreCatalogController(PublicCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/api/public/stores/{store}/availability")
    ResponseEntity<List<Availability>> availability(@PathVariable UUID store, @RequestParam List<UUID> ids) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(catalog.availability(store, ids));
    }

    /** Detalle para el modal de la tienda: sin caché porque el stock cambia con cada venta. */
    @GetMapping("/api/public/stores/{store}/products/{productId}")
    ResponseEntity<PublicCatalog.ProductDetail> product(@PathVariable UUID store, @PathVariable UUID productId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(catalog.product(store, productId));
    }

    @GetMapping("/api/public/stores/{store}/catalog")
    PublicCatalog catalog(@PathVariable UUID store, @RequestParam(defaultValue = "") String query,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "") String size,
                          @RequestParam(defaultValue = "") String color,
                          @RequestParam(defaultValue = "") String category,
                          @RequestParam(required = false) UUID variant) {
        return catalog.catalog(store, new Filters(query, size, color, category, variant), page);
    }
}
