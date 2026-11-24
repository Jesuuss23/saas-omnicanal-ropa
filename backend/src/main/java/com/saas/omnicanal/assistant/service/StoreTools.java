package com.saas.omnicanal.assistant.service;

import com.saas.omnicanal.assistant.model.AssistantModels.Item;
import com.saas.omnicanal.assistant.model.AssistantModels.StoreFacts;
import com.saas.omnicanal.catalog.model.PublicCatalog.Filters;
import com.saas.omnicanal.catalog.repository.PublicCatalogRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Herramientas que la IA puede llamar. Cada instancia está atada a una tienda: la IA no puede consultar otra.
 * Solo exponen datos públicos del catálogo (los mismos que ve cualquiera en la tienda web).
 */
public class StoreTools {
    private final UUID tenant;
    private final PublicCatalogRepository catalog;
    private final StoreFacts facts;
    final List<String> used = new ArrayList<>();
    String escalationReason;
    String escalationSummary;

    StoreTools(UUID tenant, PublicCatalogRepository catalog, StoreFacts facts) {
        this.tenant = tenant;
        this.catalog = catalog;
        this.facts = facts;
    }

    @Tool(description = "Busca prendas con stock en el catálogo de la tienda. Devuelve nombre, talla, color, precio en soles, "
            + "unidades disponibles y enlace para comprar. Úsala siempre antes de dar precios, tallas, colores o stock.")
    public List<Item> buscarPrendas(
            @ToolParam(description = "Palabras de la prenda, por ejemplo 'polo negro' o 'jean'. Vacío para ver todo.", required = false) String texto,
            @ToolParam(description = "Talla exacta, por ejemplo S, M, L, 28. Vacío para todas.", required = false) String talla,
            @ToolParam(description = "Color exacto como aparece en el catálogo. Vacío para todos.", required = false) String color) {
        used.add("buscarPrendas");
        var filters = new Filters(clean(texto, 100), clean(talla, 10), clean(color, 30), "", null);
        return catalog.variants(tenant, filters, 8, 0).stream().map(v -> new Item(v.name(), v.category(), v.size(), v.color(),
                v.price(), v.available(), facts.catalogUrl() + "?variant=" + v.id())).toList();
    }

    @Tool(description = "Datos de la tienda: dirección y horario de recojo, condiciones de delivery y enlace del catálogo web.")
    public StoreFacts infoTienda() {
        used.add("infoTienda");
        return facts;
    }

    @Tool(description = "Pasa la conversación a una persona del equipo. Úsala si el cliente pide hablar con alguien, tiene un "
            + "reclamo, pide descuento, cambio o devolución, pregunta algo que no puedes responder con las herramientas, o se molesta.")
    public String escalar(@ToolParam(description = "Motivo breve, por ejemplo 'Pide un cambio de talla'.") String motivo,
                          @ToolParam(description = "Resumen de 1 a 3 frases para la persona que tomará la conversación.") String resumen) {
        used.add("escalar");
        escalationReason = clean(motivo, 300);
        escalationSummary = clean(resumen, 2000);
        return "Listo. Dile al cliente que una persona del equipo le responderá pronto.";
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String v = value.strip();
        return v.length() > max ? v.substring(0, max) : v;
    }
}
