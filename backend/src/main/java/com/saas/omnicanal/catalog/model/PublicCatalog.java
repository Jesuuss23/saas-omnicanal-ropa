package com.saas.omnicanal.catalog.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Respuesta pública del catálogo de una tienda (sin datos internos ni secretos). */
public record PublicCatalog(String storeName, List<Variant> variants, boolean hasNext, Pickup pickup,
                            List<String> sizes, List<String> colors, List<String> categories,
                            CategoryResource resource, Brand brand) {

    public record Variant(UUID id, UUID productId, String name, String category, String size, String color,
                          BigDecimal price, int available, UUID coverImage, String subcategory,
                          BigDecimal compareAtPrice, UUID backImage) {
        public Variant(UUID id, UUID productId, String name, String category, String size, String color,
                       BigDecimal price, int available, UUID coverImage, String subcategory) {
            this(id, productId, name, category, size, color, price, available, coverImage, subcategory, null, null);
        }
    }

    /**
     * Ficha pública de un producto activo: todas sus fotos (la primera es la delantera y la segunda la trasera) y todas
     * sus variantes, incluso las agotadas, para que el cliente vea qué tallas y colores existen.
     */
    public record ProductDetail(UUID id, String name, String category, String subcategory, String description,
                                String material, List<UUID> images, List<Option> variants) {
        public record Option(UUID id, String size, String color, BigDecimal price, BigDecimal compareAtPrice,
                             Integer discountPercent, int available) {}
    }
    public record Pickup(String address, String hours) {}
    public record CategoryResource(String kind, String url) {}
    /** {@code logoVersion} es null cuando la tienda no subió logo. */
    public record Brand(String tagline, String description, String primaryColor, String whatsappPhone,
                        String instagram, Integer logoVersion) {}
    public record Availability(UUID id, BigDecimal price, int available) {}
    public record Filters(String query, String size, String color, String category, UUID variant) {}
}
