package com.saas.omnicanal.media;

import com.saas.omnicanal.catalog.repository.PublicCatalogRepository;
import com.saas.omnicanal.catalog.repository.PublicCatalogRepository.Similar;
import com.saas.omnicanal.media.VisionClient.Analysis;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Convierte una foto limpia en contexto de texto para la IA conversacional: atributos (cliente de visión) más prendas
 * parecidas del catálogo. La IA que redacta la respuesta nunca recibe la imagen, solo este JSON y su versión en texto.
 */
@Service
public class VisionCatalogService {
    private static final int MATCHES = 4;
    private final VisionClient vision;
    private final PublicCatalogRepository catalog;
    private final ObjectMapper json = new ObjectMapper();

    public VisionCatalogService(VisionClient vision, PublicCatalogRepository catalog) {
        this.vision = vision;
        this.catalog = catalog;
    }

    public boolean enabled() {
        return vision.enabled();
    }

    /** Analiza la imagen y busca coincidencias. El resultado se guarda tal cual en {@code vision_analysis_json}. */
    public JsonNode analyze(UUID tenant, byte[] image, String mimeType, String caption) {
        String categories = catalog.categoryTree(tenant).stream()
                .collect(Collectors.groupingBy(PublicCatalogRepository.CategoryCount::category, LinkedHashMap::new,
                        Collectors.mapping(c -> c.subcategory() == null ? "" : c.subcategory(), Collectors.toList())))
                .entrySet().stream().map(e -> e.getKey() + subs(e.getValue())).collect(Collectors.joining("; "));
        Analysis a = vision.analyze(image, mimeType, caption, categories);
        return withMatches(tenant, a);
    }

    /** Agrega las prendas parecidas (solo si la foto es una consulta válida de la tienda). Público para las pruebas. */
    public JsonNode withMatches(UUID tenant, Analysis a) {
        List<Similar> similar = a.esConsultaValidaTienda() ? catalog.similar(tenant, a.tipoPrenda(), a.subcategoria(), a.color(), MATCHES) : List.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("esConsultaValidaTienda", a.esConsultaValidaTienda());
        out.put("tipoPrenda", a.tipoPrenda());
        out.put("subcategoria", a.subcategoria());
        out.put("color", a.color());
        out.put("estilo", a.estilo());
        out.put("resumen", a.resumen());
        List<Map<String, Object>> matches = new ArrayList<>();
        for (Similar s : similar) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productId", s.productId().toString());
            m.put("nombre", s.name());
            m.put("categoria", s.category());
            m.put("subcategoria", s.subcategory());
            m.put("colores", s.colors());
            m.put("tallas", s.sizes());
            m.put("precioDesde", s.minPrice());
            m.put("stock", s.available());
            if (s.cover() != null) m.put("foto", s.cover().toString());
            matches.add(m);
        }
        out.put("coincidencias", matches);
        return json.valueToTree(out);
    }

    /** Lo que lee la IA conversacional en lugar de la imagen. */
    public static String context(JsonNode v, String caption) {
        var s = new StringBuilder("[El cliente envió una foto");
        if (caption != null && !caption.isBlank()) s.append(" con el texto: \"").append(caption.strip()).append("\"");
        s.append("]\nAnálisis de la foto: ").append(v.path("tipoPrenda").asText("prenda"));
        if (!v.path("subcategoria").asText("").isBlank()) s.append(" ").append(v.path("subcategoria").asText());
        if (!v.path("color").asText("").isBlank()) s.append(", color ").append(v.path("color").asText());
        if (!v.path("estilo").asText("").isBlank()) s.append(", ").append(v.path("estilo").asText());
        s.append(". ").append(v.path("resumen").asText(""));
        var matches = v.path("coincidencias");
        if (matches.isEmpty()) {
            s.append("\nNo hay prendas parecidas con stock en el catálogo: dilo con amabilidad y ofrece alternativas reales con buscarPrendas.");
        } else {
            s.append("\nPrendas parecidas con stock en el catálogo (ofrece estas opciones reales, sin inventar otras):");
            int i = 1;
            for (JsonNode m : matches) {
                s.append("\n").append(i++).append(") ").append(m.path("nombre").asText()).append(" (").append(m.path("categoria").asText(""));
                if (!m.path("subcategoria").isNull() && !m.path("subcategoria").asText("").isBlank()) s.append(" / ").append(m.path("subcategoria").asText());
                s.append("), colores ").append(m.path("colores").asText("")).append(", tallas ").append(m.path("tallas").asText(""))
                        .append(", desde S/ ").append(m.path("precioDesde").asText(""));
            }
            s.append("\nSi el cliente quiere ver una, usa enviarFotoPrenda con su nombre.");
        }
        String text = s.toString();
        return text.length() > 3900 ? text.substring(0, 3900) : text;
    }

    private static String subs(List<String> list) {
        var names = list.stream().filter(x -> !x.isBlank()).toList();
        return names.isEmpty() ? "" : " (" + String.join(", ", names) + ")";
    }
}
