package com.saas.omnicanal.catalog;

import static org.junit.jupiter.api.Assertions.*;

import com.saas.omnicanal.catalog.service.ProductCatalogService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Alta con SKU legible, categorías, un QR por producto y buscador de caja. */
@SpringBootTest
class CatalogTests {
    @Autowired ProductCatalogService catalog;
    @Autowired JdbcTemplate db;

    @Test
    void readableSkuWithoutAccentsAndWithinLength() {
        assertEquals("POLO-BASICO-NEGRO-M", ProductCatalogService.skuBase("Polo básico", "Negro", "M"));
        assertEquals("CASACA-JEAN-AZUL-ACERO-XL", ProductCatalogService.skuBase("  Casaca jean ", "Azul acero", "xl"));
        String longOne = ProductCatalogService.skuBase("Vestido largo de fiesta con encaje y pedrería bordada a mano", "Rojo vino", "32");
        assertTrue(longOne.length() <= 44 && longOne.endsWith("-ROJO-VINO-32"), longOne);
        assertEquals("PRODUCTO", ProductCatalogService.skuBase("¡¡", "", ""));
    }

    @Test
    void createGeneratesUniqueSkusAndCardsResolveQrCodes() {
        UUID store = UUID.randomUUID();
        db.update("INSERT INTO tenant(id,name) VALUES (?, 'Catálogo')", store);
        db.update("INSERT INTO tenant_subscription(tenant_id,plan_code,status,trial_ends_at) VALUES (?, 'TRIAL', 'TRIAL', now() + interval '5 days')", store);
        var price = new BigDecimal("39.90");
        UUID first = catalog.create(store, "Polo básico", "Polos", List.of(
                ProductCatalogService.normalize("", "M", "Negro", price, 3),
                ProductCatalogService.normalize(null, "S", "Negro", price, 0),
                ProductCatalogService.normalize("mi-sku", "L", "Blanco", price, 1)));
        // Mismo nombre otra vez: el SKU generado no choca con el anterior.
        UUID second = catalog.create(store, "Polo básico", " Polos ", List.of(ProductCatalogService.normalize("", "M", "Negro", price, 2)));

        var card = catalog.cards(store, List.of(first)).get(0);
        assertEquals(List.of("MI-SKU", "POLO-BASICO-NEGRO-S", "POLO-BASICO-NEGRO-M"), card.variants().stream().map(v -> v.sku()).toList(),
                "las tallas salen en orden natural dentro de cada color");
        assertEquals("POLO-BASICO-NEGRO-M-2", catalog.cards(store, List.of(second)).get(0).variants().get(0).sku());
        assertEquals(List.of("Polos"), catalog.categories(store));

        // El QR nuevo es el id del producto; el de una etiqueta antigua (variante) lleva al mismo producto.
        assertEquals(first, catalog.byCode(store, first).id());
        assertEquals(first, catalog.byCode(store, card.variants().get(0).id()).id());
        assertThrows(ResponseStatusException.class, () -> catalog.byCode(UUID.randomUUID(), first), "otra tienda no lo ve");

        assertEquals(2, catalog.search(store, "negro-m", 20).size());
        assertEquals(List.of(first), catalog.search(store, "mi-sku", 20).stream().map(c -> c.id()).toList());

        var duplicate = assertThrows(ResponseStatusException.class, () -> catalog.create(store, "Otro", null,
                List.of(ProductCatalogService.normalize("MI-SKU", "M", "Rojo", price, 1))));
        assertEquals(409, duplicate.getStatusCode().value());
    }
}
