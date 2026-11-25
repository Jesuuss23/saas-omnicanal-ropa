package com.saas.omnicanal.assistant.service;

import com.saas.omnicanal.assistant.model.AssistantModels.Attachment;
import com.saas.omnicanal.assistant.model.AssistantModels.Category;
import com.saas.omnicanal.assistant.model.AssistantModels.Item;
import com.saas.omnicanal.assistant.model.AssistantModels.Subcategory;
import com.saas.omnicanal.catalog.repository.CategoryResourceRepository;
import com.saas.omnicanal.catalog.repository.ProductImageRepository;
import com.saas.omnicanal.shared.web.PublicLinks;
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
    boolean offTopic;
    final List<Attachment> attachments = new ArrayList<>();
    private final CategoryResourceRepository resources;
    private final boolean imagesAllowed;
    private final ProductImageRepository images;

    StoreTools(UUID tenant, PublicCatalogRepository catalog, StoreFacts facts) {
        this(tenant, catalog, facts, null, false);
    }

    /** {@code imagesAllowed}: el plan permite enviar imágenes por WhatsApp (whatsapp.media.send). */
    StoreTools(UUID tenant, PublicCatalogRepository catalog, StoreFacts facts, CategoryResourceRepository resources,
               boolean imagesAllowed) {
        this(tenant, catalog, facts, resources, imagesAllowed, null);
    }

    StoreTools(UUID tenant, PublicCatalogRepository catalog, StoreFacts facts, CategoryResourceRepository resources,
               boolean imagesAllowed, ProductImageRepository images) {
        this.images = images;
        this.tenant = tenant;
        this.catalog = catalog;
        this.facts = facts;
        this.resources = resources;
        this.imagesAllowed = imagesAllowed;
    }

    @Tool(description = "Busca prendas con stock en el catálogo de la tienda. Devuelve nombre, talla, color, precio en soles, "
            + "unidades disponibles y enlace para comprar. Úsala siempre antes de dar precios, tallas, colores o stock.")
    public List<Item> buscarPrendas(
            @ToolParam(description = "Palabras de la prenda, por ejemplo 'polo negro' o 'jean'. Vacío para ver todo.", required = false) String texto,
            @ToolParam(description = "Talla exacta, por ejemplo S, M, L, 28. Vacío para todas.", required = false) String talla,
            @ToolParam(description = "Color exacto como aparece en el catálogo. Vacío para todos.", required = false) String color) {
        used.add("buscarPrendas");
        var filters = new Filters(clean(texto, 100), clean(talla, 10), clean(color, 30), "", null);
        return catalog.variants(tenant, filters, 8, 0).stream().map(v -> new Item(v.name(), v.category(), v.subcategory(), v.size(), v.color(),
                v.price(), v.available(), facts.catalogUrl() == null ? null : facts.catalogUrl() + "?variant=" + v.id())).toList();
    }

    @Tool(description = "Categorías de la tienda (por ejemplo polos, jeans) con sus subcategorías o estilos (por ejemplo polos "
            + "retro, polos de verano), cuántas prendas con stock hay y si la categoría tiene catálogo para enviar. Úsala cuando el "
            + "cliente pregunta por un tipo de prenda en general o por lo que vende la tienda.")
    public List<Category> categorias() {
        used.add("categorias");
        var tree = new java.util.LinkedHashMap<String, List<Subcategory>>();
        var totals = new java.util.HashMap<String, Integer>();
        for (var c : catalog.categoryTree(tenant)) {
            tree.computeIfAbsent(c.category(), k -> new ArrayList<>());
            totals.merge(c.category(), c.products(), Integer::sum);
            if (c.subcategory() != null) tree.get(c.category()).add(new Subcategory(c.subcategory(), c.products()));
        }
        return tree.entrySet().stream().map(e -> new Category(e.getKey(), totals.get(e.getKey()), e.getValue(),
                resources != null && usable(resources.options(tenant, e.getKey())) != null)).toList();
    }

    @Tool(description = "Envía al cliente el catálogo de una categoría (PDF, imagen o enlace, el que la tienda configuró). "
            + "Úsala cuando el cliente lo pida o acepte recibirlo. Si devuelve un enlace, compártelo tal cual en tu respuesta; "
            + "si dice que se adjunta un archivo, avisa que se lo envías.")
    public String enviarCatalogo(@ToolParam(description = "Categoría exacta, como la devuelve categorias, por ejemplo 'Polos'.") String categoria) {
        used.add("enviarCatalogo");
        if (resources == null) return "Esta tienda no tiene catálogos por categoría. Ofrece buscar prendas con buscarPrendas.";
        var name = resources.category(tenant, clean(categoria, 50));
        if (name.isEmpty()) return "No existe esa categoría. Usa categorias para ver las que hay.";
        if (attachments.stream().anyMatch(a -> a.description().startsWith("Catálogo") || a.description().startsWith("Imagen del catálogo")))
            return "Ya se adjuntó un catálogo en esta respuesta.";
        var option = usable(resources.options(tenant, name.get()));
        if (option == null) return "La categoría " + name.get() + " no tiene catálogo para enviar. Ofrece buscar prendas con buscarPrendas.";
        if (option.file() == null) return "Comparte este enlace del catálogo de " + name.get() + ": " + option.url();
        boolean pdf = "PDF".equals(option.kind());
        String file = "Catalogo " + name.get() + (pdf ? ".pdf" : option.mediaType() != null && option.mediaType().contains("png") ? ".png" : ".jpg");
        attachments.add(new Attachment(pdf ? "DOCUMENT" : "IMAGE", option.mediaType(), file, option.file(),
                (pdf ? "Catálogo PDF de " : "Imagen del catálogo de ") + name.get()));
        return "Listo: el " + (pdf ? "PDF" : "imagen") + " del catálogo de " + name.get()
                + " se enviará después de tu mensaje. Avísale al cliente que se lo envías; no escribas ningún enlace.";
    }

    @Tool(description = "Envía al cliente la foto principal de una prenda del catálogo (después de tu mensaje). Úsala cuando el "
            + "cliente quiera ver una prenda, por ejemplo una de las parecidas a la foto que envió. Si el plan no permite fotos, "
            + "comparte el enlace de la prenda.")
    public String enviarFotoPrenda(@ToolParam(description = "Nombre de la prenda como aparece en el catálogo.") String prenda) {
        used.add("enviarFotoPrenda");
        var found = catalog.variants(tenant, new Filters(clean(prenda, 100), "", "", "", null), 1, 0);
        if (found.isEmpty()) return "No encontré esa prenda con stock. Usa buscarPrendas para ver opciones.";
        var v = found.get(0);
        String link = facts.catalogUrl() == null ? null : facts.catalogUrl() + "?variant=" + v.id();
        if (!imagesAllowed || images == null)
            return link == null ? "Tu plan no permite enviar fotos por WhatsApp. Describe la prenda con sus datos."
                    : "Tu plan no permite enviar fotos por WhatsApp. Comparte este enlace de la prenda: " + link;
        if (v.coverImage() == null) return "Esa prenda todavía no tiene foto. Describe la prenda con sus datos.";
        if (attachments.size() >= 3) return "Ya se adjuntaron suficientes archivos en esta respuesta.";
        var image = images.read(tenant, v.coverImage());
        if (image.isEmpty()) return "Esa prenda todavía no tiene foto. Describe la prenda con sus datos.";
        String ext = image.get().mediaType().contains("png") ? ".png" : image.get().mediaType().contains("webp") ? ".webp" : ".jpg";
        attachments.add(new Attachment("IMAGE", image.get().mediaType(), v.name() + ext, image.get().content(), "Foto de " + v.name()));
        return "Listo: la foto de " + v.name() + " se enviará después de tu mensaje. Avísale al cliente.";
    }

    /** Primer recurso que se puede enviar: archivo (las imágenes solo con el plan) o enlace público. Solo uno. */
    private CategoryResourceRepository.Option usable(List<CategoryResourceRepository.Option> options) {
        for (var o : options) {
            if (o.file() != null) {
                if ("IMAGE".equals(o.kind()) && !imagesAllowed) continue;
                return o;
            }
            if (PublicLinks.isPublic(o.url())) return o;
        }
        return null;
    }

    @Tool(description = "Datos de la tienda: dirección y horario de recojo, condiciones de delivery y enlace del catálogo web.")
    public StoreFacts infoTienda() {
        used.add("infoTienda");
        return facts;
    }

    @Tool(description = "Pasa la conversación a una persona del equipo. Úsala si el cliente pide hablar con alguien, tiene un "
            + "reclamo, pide descuento, cambio o devolución, hace una consulta de la tienda que no puedes responder con las "
            + "herramientas, o se molesta. Nunca la uses para preguntas ajenas a la tienda: para eso está fueraDeTema.")
    public String escalar(@ToolParam(description = "Motivo breve, por ejemplo 'Pide un cambio de talla'.") String motivo,
                          @ToolParam(description = "Resumen de 1 a 3 frases para la persona que tomará la conversación.") String resumen) {
        used.add("escalar");
        escalationReason = clean(motivo, 300);
        escalationSummary = clean(resumen, 2000);
        return "Listo. Dile al cliente que una persona del equipo le responderá pronto.";
    }

    @Tool(description = "Marca el mensaje como ajeno a la tienda. Úsala cuando el cliente pregunta algo que no tiene que ver con "
            + "las prendas, precios, tallas, stock, envíos, recojo, pedidos o políticas de la tienda (por ejemplo tareas, historia, "
            + "clima, noticias, chistes, recetas o programación). No la uses para saludos, agradecimientos ni despedidas.")
    public String fueraDeTema(@ToolParam(description = "Tema de la pregunta en 1 a 4 palabras, por ejemplo 'tarea de historia'.", required = false) String tema) {
        used.add("fueraDeTema");
        offTopic = true;
        return "Registrado. No respondas la pregunta; el sistema enviará el aviso al cliente.";
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String v = value.strip();
        return v.length() > max ? v.substring(0, max) : v;
    }
}
