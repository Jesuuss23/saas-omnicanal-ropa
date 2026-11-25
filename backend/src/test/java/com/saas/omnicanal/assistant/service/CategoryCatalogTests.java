package com.saas.omnicanal.assistant.service;

import static org.junit.jupiter.api.Assertions.*;

import com.saas.omnicanal.assistant.model.AssistantModels.StoreFacts;
import com.saas.omnicanal.catalog.repository.CategoryResourceRepository;
import com.saas.omnicanal.catalog.repository.PublicCatalogRepository;
import com.saas.omnicanal.catalog.service.CatalogPdfService;
import com.saas.omnicanal.shared.web.PublicLinks;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Subcategorías, PDF por categoría y el recurso único (PDF → imagen → enlace) que usa la IA. */
@SpringBootTest
class CategoryCatalogTests {
    @Autowired JdbcTemplate db;
    @Autowired PublicCatalogRepository catalog;
    @Autowired CategoryResourceRepository resources;
    @Autowired CatalogPdfService pdfs;

    UUID store;

    @BeforeEach
    void setUp() {
        store = UUID.randomUUID();
        db.update("INSERT INTO tenant(id,name) VALUES (?, 'Tienda Polos')", store);
        product("Polo Retro 90", "Polos", "Retro", 59.90, 3);
        product("Polo Lino", "Polos", "Verano", 49.90, 2);
        product("Polo Térmico", "polos", "Invierno", 69.90, 1);
        product("Jean Mom", "Jeans", null, 119.00, 4);
        product("Polo agotado", "Polos", "Retro", 39.90, 0);
    }

    @Test
    void toolsDescribeCategoriesWithTheirSubcategories() {
        var tools = new StoreTools(store, catalog, facts(), resources, false);
        var categories = tools.categorias();
        var polos = categories.stream().filter(c -> c.categoria().equalsIgnoreCase("Polos")).findFirst().orElseThrow();
        assertEquals(List.of("Invierno", "Retro", "Verano"), polos.subcategorias().stream().map(s -> s.nombre()).sorted().toList());
        assertEquals(3, polos.prendas());
        assertFalse(polos.catalogo());
        assertEquals("Retro", tools.buscarPrendas("retro", "", "").get(0).subcategoria());
        String prompt = GeminiResponder.system(facts(), null, null);
        assertTrue(prompt.contains("pregúntale cuál se adecua mejor"));
        assertTrue(prompt.contains("[Nota de voz]"));
    }

    @Test
    void generatedPdfBecomesTheCategoryCatalogAndIsSentAsAFile() {
        var pdf = pdfs.generate(store, "polos");
        assertEquals(3, pdf.products());
        assertTrue(new String(pdf.content(), 0, 5, StandardCharsets.US_ASCII).startsWith("%PDF"));
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> pdfs.generate(store, "Casacas")).getStatusCode().value());

        String url = pdfs.saveAsResource(store, "Polos");
        assertTrue(url.startsWith("/catalogos/"));
        var tools = new StoreTools(store, catalog, facts(), resources, false);
        assertTrue(tools.enviarCatalogo("POLOS").contains("se enviará"));
        assertEquals(1, tools.attachments.size());
        assertEquals("DOCUMENT", tools.attachments.get(0).kind());
        assertEquals("application/pdf", tools.attachments.get(0).mimeType());
        // Solo un recurso por respuesta.
        assertTrue(tools.enviarCatalogo("Polos").contains("Ya se adjuntó"));
        assertEquals(1, tools.attachments.size());
        // Regenerar reemplaza el archivo anterior.
        pdfs.saveAsResource(store, "Polos");
        assertEquals(1, db.queryForObject("SELECT count(*) FROM category_catalog_file WHERE tenant_id=? AND kind='PDF'", Integer.class, store));
    }

    @Test
    void onlyOneResourceIsUsedFollowingTheChoiceThenPdfImageLink() {
        UUID image = UUID.randomUUID();
        db.update("""
                INSERT INTO category_catalog_resource(tenant_id,category,link_url,image_url,active,revision)
                VALUES (?,'Jeans','https://fotos.example.pe/jeans',?, 'IMAGE', 1)
                """, store, "/catalogos/" + image);
        db.update("INSERT INTO category_catalog_file(id,tenant_id,category,kind,media_type,content) VALUES (?,?,'Jeans','IMAGE','image/png',?)",
                image, store, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        // Elegida la imagen, pero el plan no permite imágenes: usa el siguiente, el enlace.
        var basic = new StoreTools(store, catalog, facts(), resources, false);
        assertTrue(basic.enviarCatalogo("Jeans").contains("https://fotos.example.pe/jeans"));
        assertTrue(basic.attachments.isEmpty());
        var premium = new StoreTools(store, catalog, facts(), resources, true);
        premium.enviarCatalogo("Jeans");
        assertEquals("IMAGE", premium.attachments.get(0).kind());

        // Automático con enlace local: nunca se comparte.
        db.update("UPDATE category_catalog_resource SET active='NONE', image_url=NULL, link_url='http://localhost:3000/tienda' WHERE tenant_id=?", store);
        var local = new StoreTools(store, catalog, facts(), resources, true);
        assertTrue(local.enviarCatalogo("Jeans").contains("no tiene catálogo"));
    }

    @Test
    void privateLinksAreRemoved() {
        assertTrue(PublicLinks.isPublic("https://mitienda.pe/tienda/x"));
        assertFalse(PublicLinks.isPublic("http://localhost:3000/tienda/x"));
        assertFalse(PublicLinks.isPublic("http://127.0.0.1:8080"));
        assertFalse(PublicLinks.isPublic("http://192.168.1.10/catalogo"));
        assertFalse(PublicLinks.isPublic("http://host.docker.internal:3000"));
        String clean = PublicLinks.stripPrivate("Mira aquí: http://localhost:3000/tienda/abc?variant=1 o en https://mitienda.pe/polos");
        assertFalse(clean.contains("localhost"));
        assertTrue(clean.contains("https://mitienda.pe/polos"));
    }

    private StoreFacts facts() {
        return new StoreFacts("Tienda Polos", null, null, null, false, null);
    }

    private void product(String name, String category, String subcategory, double price, int stock) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO product(id,tenant_id,name,category,subcategory) VALUES (?,?,?,?,?)", id, store, name, category, subcategory);
        db.update("INSERT INTO product_variant(product_id,tenant_id,sku,size,color,price,stock_physical) VALUES (?,?,?,'M','Negro',?,?)",
                id, store, "SKU-" + id.toString().substring(0, 8), price, stock);
    }
}
