package com.saas.omnicanal.catalog.model;

/** Datos de ficha del producto: descripción, material y si se muestra en la tienda y al asistente. */
public record ProductDetails(String description, String material, boolean active) {
    public static final ProductDetails EMPTY = new ProductDetails(null, null, true);
}
