package com.saas.omnicanal.catalog.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Reglas de precio y disponibilidad del catálogo, en un solo lugar (el frontend replica las mismas en lib/pricing.ts).
 *
 * <ul>
 *   <li><b>Disponible</b> = stock físico − stock reservado, nunca menor que 0.</li>
 *   <li><b>Margen %</b> = (precio − costo) / precio × 100, con un decimal. Sin costo no hay margen.</li>
 *   <li><b>Descuento %</b> = (precio antes − precio) / precio antes × 100, redondeado hacia abajo. Solo existe
 *       si el precio antes (tachado) es mayor que el precio actual.</li>
 *   <li><b>Estado</b>: INACTIVE si el producto está oculto; si no, SOLD_OUT con 0 disponibles, LOW_STOCK con
 *       {@value #LOW_STOCK} o menos, y AVAILABLE en el resto.</li>
 * </ul>
 */
public final class PricingRules {
    public static final int LOW_STOCK = 3;

    private PricingRules() {}

    public static int available(int physical, int reserved) {
        return Math.max(0, physical - reserved);
    }

    public static BigDecimal marginPercent(BigDecimal price, BigDecimal cost) {
        if (price == null || cost == null || price.signum() <= 0) return null;
        return price.subtract(cost).multiply(BigDecimal.valueOf(100)).divide(price, 1, RoundingMode.HALF_UP);
    }

    public static Integer discountPercent(BigDecimal price, BigDecimal compareAt) {
        if (price == null || compareAt == null || compareAt.compareTo(price) <= 0) return null;
        return compareAt.subtract(price).multiply(BigDecimal.valueOf(100)).divide(compareAt, 0, RoundingMode.FLOOR).intValue();
    }

    public static String status(boolean active, int available) {
        if (!active) return "INACTIVE";
        if (available <= 0) return "SOLD_OUT";
        return available <= LOW_STOCK ? "LOW_STOCK" : "AVAILABLE";
    }
}
