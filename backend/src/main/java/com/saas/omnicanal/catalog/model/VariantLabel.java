package com.saas.omnicanal.catalog.model;

import java.math.BigDecimal;
import java.util.UUID;

/** Datos impresos en la etiqueta de una variante (talla y color concretos). */
public record VariantLabel(UUID id, String name, String sku, String size, String color, BigDecimal price) {}
