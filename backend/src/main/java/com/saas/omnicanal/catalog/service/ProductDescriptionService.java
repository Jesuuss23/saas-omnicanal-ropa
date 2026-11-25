package com.saas.omnicanal.catalog.service;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Descripción de producto: un borrador armado con los datos de la ficha (siempre disponible) y, si hay IA
 * configurada, una versión redactada por Gemini a partir de esos mismos datos. Nunca inventa precios ni stock.
 */
@Service
public class ProductDescriptionService {
    private static final Logger log = LoggerFactory.getLogger(ProductDescriptionService.class);
    static final int MAX = 2000;

    private final DescriptionWriter writer;

    public ProductDescriptionService(DescriptionWriter writer) {
        this.writer = writer;
    }

    public record Facts(String name, String category, String subcategory, String material, List<String> colors,
                        List<String> sizes, String notes) {}

    public record Result(String description, boolean ai) {}

    public Result generate(Facts f) {
        if (f.name() == null || f.name().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe el nombre del producto");
        if (!writer.configured()) return new Result(draft(f), false);
        try {
            String text = writer.write(SYSTEM, prompt(f));
            if (text == null || text.isBlank()) return new Result(draft(f), false);
            String clean = text.strip().replaceAll("(?m)^#+\\s*", "").replace("**", "");
            return new Result(clean.length() > MAX ? clean.substring(0, MAX).strip() : clean, true);
        } catch (RuntimeException ex) {
            log.warn("Descripción con IA: falló Gemini, se usa el borrador → {}", ex.toString());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "La IA no respondió. Intenta de nuevo en un momento.", ex);
        }
    }

    static final String SYSTEM = """
            Eres redactor de fichas de producto para una tienda de ropa en Perú. Escribe en español neutro y cercano.
            Reglas: 2 a 4 frases (máximo 600 caracteres), sin títulos, sin viñetas, sin emojis, sin markdown.
            Usa solo los datos que te dan: no inventes precios, stock, descuentos, tallas, colores ni materiales.
            Destaca para qué ocasión sirve, cómo combinarla y la sensación al usarla. Termina con las tallas y colores disponibles.
            """;

    static String prompt(Facts f) {
        var s = new StringBuilder("Datos del producto:\n");
        line(s, "Nombre", f.name());
        line(s, "Categoría", f.category());
        line(s, "Subcategoría", f.subcategory());
        line(s, "Material", f.material());
        line(s, "Colores", join(f.colors()));
        line(s, "Tallas", join(f.sizes()));
        line(s, "Notas del dueño", f.notes());
        return s.append("Escribe la descripción.").toString();
    }

    /** Borrador sin IA: "Polo Retro en algodón. Categoría Polos · Retro. Colores: Negro y Blanco. Tallas: S, M y L." */
    public static String draft(Facts f) {
        var s = new StringBuilder(f.name().strip());
        if (present(f.material())) s.append(" en ").append(f.material().strip().toLowerCase());
        s.append('.');
        if (present(f.category())) {
            s.append(" Categoría ").append(f.category().strip());
            if (present(f.subcategory())) s.append(" · ").append(f.subcategory().strip());
            s.append('.');
        }
        String colors = join(f.colors()), sizes = join(f.sizes());
        if (colors != null) s.append(" Colores: ").append(colors).append('.');
        if (sizes != null) s.append(" Tallas: ").append(sizes).append('.');
        return s.toString();
    }

    private static void line(StringBuilder s, String label, String value) {
        if (present(value)) s.append("- ").append(label).append(": ").append(value.strip()).append('\n');
    }

    private static boolean present(String v) {
        return v != null && !v.isBlank();
    }

    static String join(List<String> items) {
        if (items == null) return null;
        var list = items.stream().filter(ProductDescriptionService::present).map(String::strip).distinct().toList();
        if (list.isEmpty()) return null;
        if (list.size() == 1) return list.get(0);
        return String.join(", ", list.subList(0, list.size() - 1)) + " y " + list.get(list.size() - 1);
    }
}
