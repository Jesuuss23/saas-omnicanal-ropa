package com.saas.omnicanal.catalog.model;

import java.math.BigDecimal;
import java.util.UUID;

/** Variante del catálogo del dueño con su stock y versiones (xmin) para edición optimista. */
public record CatalogRow(UUID id, String name, String sku, String size, String color, BigDecimal price, int available,
                         String category, int physical, int reserved, String productVersion, String variantVersion,
                         UUID productId, UUID coverImage) {
}
