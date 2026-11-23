package com.saas.omnicanal.catalog.model;

import java.math.BigDecimal;

/** Datos normalizados de una variante (SKU en mayúsculas, textos recortados). */
public record VariantDraft(String sku, String size, String color, BigDecimal price, int stock) {
}
