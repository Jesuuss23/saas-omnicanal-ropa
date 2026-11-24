package com.saas.omnicanal.catalog.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Un producto con todas sus variantes: lo usan las etiquetas (un QR por producto) y el punto de venta. */
public record ProductCard(UUID id, String name, String category, UUID coverImage, List<Option> variants) {
    public record Option(UUID id, String sku, String size, String color, BigDecimal price, int available) {}
}
