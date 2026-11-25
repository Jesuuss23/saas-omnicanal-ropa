package com.saas.omnicanal.catalog.model;

import java.math.BigDecimal;

/**
 * Datos normalizados de una variante (SKU en mayúsculas, textos recortados). {@code compareAtPrice} es el precio
 * "antes" que se muestra tachado y {@code cost} el costo para calcular el margen; ambos son opcionales.
 */
public record VariantDraft(String sku, String size, String color, BigDecimal price, int stock,
                           BigDecimal compareAtPrice, BigDecimal cost) {
    public VariantDraft(String sku, String size, String color, BigDecimal price, int stock) {
        this(sku, size, color, price, stock, null, null);
    }

    public VariantDraft withSku(String value) {
        return new VariantDraft(value, size, color, price, stock, compareAtPrice, cost);
    }
}
