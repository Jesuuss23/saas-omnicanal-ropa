package com.saas.omnicanal.catalog;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.saas.omnicanal.catalog.model.PricingRules;
import com.saas.omnicanal.catalog.service.DescriptionWriter;
import com.saas.omnicanal.catalog.service.ProductCatalogService;
import com.saas.omnicanal.catalog.service.ProductDescriptionService;
import com.saas.omnicanal.catalog.service.PublicCatalogService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

/** Precio tachado, costo y margen, estados activo/agotado, filtros rápidos, descripción con IA y ficha pública. */
@SpringBootTest
class CatalogEcommerceTests {
    @Autowired ProductCatalogService catalog;
    @Autowired ProductDescriptionService descriptions;
    @Autowired PublicCatalogService publicCatalog;
    @Autowired JdbcTemplate db;
    @MockitoBean DescriptionWriter writer;

    @Test
    void pricingFormulas() {
        assertEquals(new BigDecimal("50.0"), PricingRules.marginPercent(new BigDecimal("40.00"), new BigDecimal("20.00")));
        assertNull(PricingRules.marginPercent(new BigDecimal("40.00"), null));
        assertEquals(33, PricingRules.discountPercent(new BigDecimal("39.90"), new BigDecimal("59.90")));
        assertNull(PricingRules.discountPercent(new BigDecimal("39.90"), new BigDecimal("39.90")));
        assertEquals(0, PricingRules.available(2, 5));
        assertEquals("INACTIVE", PricingRules.status(false, 10));
        assertEquals("SOLD_OUT", PricingRules.status(true, 0));
        assertEquals("LOW_STOCK", PricingRules.status(true, 3));
        assertEquals("AVAILABLE", PricingRules.status(true, 4));
    }

    @Test
    void compareAtMustBeAboveThePrice() {
        var ex = assertThrows(ResponseStatusException.class, () -> ProductCatalogService.normalize("", "M", "Negro",
                new BigDecimal("40"), 1, new BigDecimal("40"), null));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void quickFiltersActiveStateAndPublicDetail() {
        UUID store = store();
        var price = new BigDecimal("39.90");
        UUID polo = catalog.create(store, "Polo retro", "Polos", "Retro",
                ProductCatalogService.details("Polo de algodón peinado.", "Algodón", true), List.of(
                        ProductCatalogService.normalize("", "M", "Negro", price, 10, new BigDecimal("59.90"), new BigDecimal("20")),
                        ProductCatalogService.normalize("", "L", "Negro", price, 2, null, null),
                        ProductCatalogService.normalize("", "S", "Negro", price, 0, null, null)));
        UUID jean = catalog.create(store, "Jean recto", "Pantalones", null, List.of(
                ProductCatalogService.normalize("", "30", "Azul", new BigDecimal("89"), 5)));

        var all = catalog.page(store, 0, "", "all", "").rows();
        assertEquals(4, all.size());
        var m = all.stream().filter(r -> "M".equals(r.size())).findFirst().orElseThrow();
        assertEquals(33, m.discountPercent());
        assertEquals(new BigDecimal("49.9"), m.marginPercent());
        assertEquals("AVAILABLE", m.status());
        assertEquals("Algodón", m.material());
        assertEquals(List.of("S"), catalog.page(store, 0, "", "soldout", "").rows().stream().map(r -> r.size()).toList());
        assertEquals(List.of("L"), catalog.page(store, 0, "", "low", "").rows().stream().map(r -> r.size()).toList());
        assertEquals(1, catalog.page(store, 0, "", "all", "pantalones").rows().size());
        assertThrows(ResponseStatusException.class, () -> catalog.page(store, 0, "", "raro", ""));

        var detail = publicCatalog.product(store, polo);
        assertEquals("Polo de algodón peinado.", detail.description());
        assertEquals(3, detail.variants().size(), "las agotadas también se listan para mostrar la talla sin stock");
        assertEquals(33, detail.variants().stream().filter(o -> "M".equals(o.size())).findFirst().orElseThrow().discountPercent());

        catalog.setActive(store, jean, false);
        assertEquals(List.of("30"), catalog.page(store, 0, "", "inactive", "").rows().stream().map(r -> r.size()).toList());
        assertEquals("INACTIVE", catalog.page(store, 0, "", "inactive", "").rows().get(0).status());
        assertThrows(ResponseStatusException.class, () -> publicCatalog.product(store, jean));
        assertTrue(publicCatalog.catalog(store, new com.saas.omnicanal.catalog.model.PublicCatalog.Filters("", "", "", "", null), 0)
                .variants().stream().noneMatch(v -> v.productId().equals(jean)), "un producto oculto no sale en la tienda");
        assertThrows(ResponseStatusException.class, () -> publicCatalog.product(UUID.randomUUID(), polo));
    }

    @Test
    void descriptionDraftWithoutAiAndGeneratedWithAi() {
        var facts = new ProductDescriptionService.Facts("Polo retro", "Polos", "Retro", "Algodón",
                List.of("Negro", "Blanco"), List.of("S", "M", "L"), null);
        when(writer.configured()).thenReturn(false);
        var draft = descriptions.generate(facts);
        assertFalse(draft.ai());
        assertEquals("Polo retro en algodón. Categoría Polos · Retro. Colores: Negro y Blanco. Tallas: S, M y L.", draft.description());
        verify(writer, never()).write(anyString(), anyString());

        when(writer.configured()).thenReturn(true);
        when(writer.write(anyString(), anyString())).thenReturn("## **Polo retro** fresco para el día a día.");
        var ai = descriptions.generate(facts);
        assertTrue(ai.ai());
        assertEquals("Polo retro fresco para el día a día.", ai.description());
        verify(writer).write(anyString(), argThat(p -> p.contains("Material: Algodón") && p.contains("Tallas: S, M y L")));
    }

    private UUID store() {
        UUID store = UUID.randomUUID();
        db.update("INSERT INTO tenant(id,name) VALUES (?, 'Tienda ecommerce')", store);
        db.update("INSERT INTO tenant_subscription(tenant_id,plan_code,status,trial_ends_at) VALUES (?, 'TRIAL', 'TRIAL', now() + interval '5 days')", store);
        return store;
    }
}
