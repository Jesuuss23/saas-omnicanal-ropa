package com.saas.omnicanal.catalog.service;

import com.saas.omnicanal.catalog.repository.CategoryResourceRepository;
import com.saas.omnicanal.shared.storage.MediaStorage;
import com.saas.omnicanal.shared.storage.StoredFile;
import com.saas.omnicanal.catalog.repository.CategoryResourceRepository.PdfItem;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Catálogo PDF de una categoría: portada de cada producto, subcategoría, precio, tallas y colores. Cada página se dibuja
 * como imagen (Java2D), así se ven bien tildes, ñ y cualquier nombre, y el archivo queda liviano para WhatsApp.
 */
@Service
public class CatalogPdfService {
    private static final int W = 1240, H = 1754, COLS = 2, ROWS = 3, MARGIN = 70, HEADER = 190, FOOTER = 70;
    private final CategoryResourceRepository resources;

    private final MediaStorage storage;

    public CatalogPdfService(CategoryResourceRepository resources, MediaStorage storage) {
        this.resources = resources;
        this.storage = storage;
    }

    public record Pdf(String category, byte[] content, int products) {}

    public Pdf generate(UUID tenant, String requested) {
        String category = resources.category(tenant, requested)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No hay productos en esa categoría."));
        List<PdfItem> items = resources.pdfItems(tenant, category);
        if (items.isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La categoría " + category + " no tiene prendas con stock.");
        String store = resources.storeName(tenant);
        int perPage = COLS * ROWS, pages = (items.size() + perPage - 1) / perPage;
        try (var pdf = new PDDocument(); var out = new ByteArrayOutputStream()) {
            for (int p = 0; p < pages; p++) {
                BufferedImage canvas = page(store, category, items.subList(p * perPage, Math.min(items.size(), (p + 1) * perPage)), p + 1, pages);
                var page = new PDPage(PDRectangle.A4);
                pdf.addPage(page);
                var image = JPEGFactory.createFromImage(pdf, canvas, 0.78f);
                try (var stream = new PDPageContentStream(pdf, page)) {
                    stream.drawImage(image, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
                }
            }
            pdf.getDocumentInformation().setTitle("Catálogo " + category + " · " + store);
            pdf.save(out);
            byte[] bytes = out.toByteArray();
            if (bytes.length > 10 * 1024 * 1024)
                throw new ResponseStatusException(HttpStatus.valueOf(413), "El catálogo supera 10 MB. Divide la categoría en subcategorías.");
            return new Pdf(category, bytes, items.size());
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo generar el PDF.", ex);
        }
    }

    /** Genera el PDF y lo deja como recurso PDF de la categoría (el que comparte la IA). */
    @Transactional
    public String saveAsResource(UUID tenant, String category) {
        Pdf pdf = generate(tenant, category);
        StoredFile stored = null;
        if (storage.enabled()) {
            try {
                stored = storage.upload(tenant, MediaStorage.Folder.CATALOGS, pdf.content(), "application/pdf", "catalogo.pdf");
            } catch (MediaStorage.StorageException e) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo guardar el PDF. Inténtalo otra vez.");
            }
            storage.deleteOnRollback(stored);
        }
        return resources.savePdf(tenant, pdf.category(), pdf.content(), stored,
                removed -> removed.forEach(r -> storage.deleteAfterCommit(r.publicId(), r.resourceType())));
    }

    private BufferedImage page(String store, String category, List<PdfItem> items, int number, int pages) throws IOException {
        var canvas = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, W, H);
            g.setColor(new Color(0x1F2937));
            text(g, store, MARGIN, 95, Font.BOLD, 52, W - 2 * MARGIN);
            g.setColor(new Color(0x6B7280));
            text(g, "Catálogo · " + category + " · " + LocalDate.now(ZoneId.of("America/Lima"))
                    .format(DateTimeFormatter.ofPattern("d 'de' MMMM yyyy", Locale.forLanguageTag("es-PE"))), MARGIN, 145, Font.PLAIN, 30, W - 2 * MARGIN);
            g.setColor(new Color(0xE5E7EB));
            g.fillRect(MARGIN, 168, W - 2 * MARGIN, 3);
            int cellW = (W - 2 * MARGIN - 40) / COLS, cellH = (H - HEADER - FOOTER - 40) / ROWS;
            for (int i = 0; i < items.size(); i++) {
                int x = MARGIN + (i % COLS) * (cellW + 40), y = HEADER + 20 + (i / COLS) * cellH;
                card(g, items.get(i), x, y, cellW, cellH - 30);
            }
            g.setColor(new Color(0x9CA3AF));
            text(g, "Precios en soles. Stock sujeto a disponibilidad.   Página " + number + " de " + pages, MARGIN, H - 35, Font.PLAIN, 22, W - 2 * MARGIN);
        } finally {
            g.dispose();
        }
        return canvas;
    }

    private void card(Graphics2D g, PdfItem item, int x, int y, int w, int h) throws IOException {
        int imageH = h - 175;
        g.setColor(new Color(0xF3F4F6));
        g.fillRoundRect(x, y, w, imageH, 24, 24);
        byte[] bytes = photo(item);
        BufferedImage photo = bytes == null ? null : ImageIO.read(new ByteArrayInputStream(bytes));
        if (photo != null) {
            double scale = Math.min((double) w / photo.getWidth(), (double) imageH / photo.getHeight());
            int pw = (int) (photo.getWidth() * scale), ph = (int) (photo.getHeight() * scale);
            g.drawImage(photo, x + (w - pw) / 2, y + (imageH - ph) / 2, pw, ph, null);
        } else {
            g.setColor(new Color(0x9CA3AF));
            text(g, "Sin foto", x + w / 2 - 60, y + imageH / 2, Font.PLAIN, 28, w);
        }
        int ty = y + imageH + 48;
        g.setColor(new Color(0x111827));
        text(g, item.name(), x, ty, Font.BOLD, 34, w);
        g.setColor(new Color(0x4B5563));
        if (item.subcategory() != null) text(g, item.subcategory(), x, ty + 38, Font.PLAIN, 26, w);
        g.setColor(new Color(0x111827));
        text(g, price(item.minPrice(), item.maxPrice()), x, ty + 82, Font.BOLD, 32, w);
        g.setColor(new Color(0x4B5563));
        text(g, "Tallas: " + nz(item.sizes()) + "   Colores: " + nz(item.colors()), x, ty + 118, Font.PLAIN, 24, w);
    }

    static String price(BigDecimal min, BigDecimal max) {
        String a = "S/ " + min.setScale(2, RoundingMode.HALF_UP).toPlainString();
        return min.compareTo(max) == 0 ? a : a + " – " + max.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private static void text(Graphics2D g, String text, int x, int baseline, int style, int size, int maxWidth) {
        Font font = new Font(Font.SANS_SERIF, style, size);
        g.setFont(font);
        while (g.getFontMetrics().stringWidth(text) > maxWidth && size > 18) g.setFont(font.deriveFont((float) --size));
        if (g.getFontMetrics().stringWidth(text) > maxWidth) {
            while (text.length() > 1 && g.getFontMetrics().stringWidth(text + "…") > maxWidth)
                text = text.substring(0, text.offsetByCodePoints(text.length(), -1));
            text += "…";
        }
        g.drawString(text, x, baseline);
    }

    /** Portada de la prenda: de la base o, si está en Cloudinary, una versión reducida en JPG (más liviana para el PDF). */
    private byte[] photo(PdfItem item) {
        if (item.image() != null) return item.image();
        if (item.imageUrl() == null || !storage.enabled()) return null;
        try {
            return storage.download(item.imageUrl().replace("/image/upload/", "/image/upload/c_limit,w_700,f_jpg/"));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
