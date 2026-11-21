package com.saas.omnicanal.catalog;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.Principal;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/owner/stores/{store}/catalog/labels")
class VariantLabelsApi {
    private final NamedParameterJdbcTemplate db;

    VariantLabelsApi(NamedParameterJdbcTemplate db) {
        this.db = db;
    }

    record Item(@NotNull UUID id, @Min(1) @Max(300) int copies) {}
    record Batch(@NotEmpty @Size(max=100) List<@NotNull @Valid Item> items) {}
    record Variant(UUID id, String name, String sku, String size, String color) {}
    record Download(byte[] pdf) {}

    @PostMapping
    Download generate(@PathVariable UUID store, Principal owner, @Valid @RequestBody Batch batch)
            throws IOException, WriterException {
        if (!store.toString().equals(owner.getName())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var ids = batch.items().stream().map(Item::id).distinct().toList();
        int total = batch.items().stream().mapToInt(Item::copies).sum();
        if (total > 300 || ids.size() != batch.items().size()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);

        Map<UUID, Variant> variants = new HashMap<>();
        db.query("""
                SELECT v.id, p.name, v.sku, v.size, v.color
                FROM product_variant v JOIN product p ON p.id = v.product_id AND p.tenant_id = v.tenant_id
                WHERE v.tenant_id = :store AND v.id IN (:ids)
                """, Map.of("store", store, "ids", ids), rs -> {
            UUID id = rs.getObject("id", UUID.class);
            variants.put(id, new Variant(id, rs.getString("name"), rs.getString("sku"), rs.getString("size"), rs.getString("color")));
        });
        if (variants.size() != ids.size()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);

        try (var pdf = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            int index = 0;
            PDPage page = null;
            for (Item item : batch.items()) {
                var image = LosslessFactory.createFromImage(pdf, label(variants.get(item.id())));
                for (int copy = 0; copy < item.copies(); copy++, index++) {
                    int cell = index % 18;
                    if (cell == 0) {
                        page = new PDPage(PDRectangle.A4);
                        pdf.addPage(page);
                    }
                    float x = 18 + (cell % 2) * 280;
                    float y = PDRectangle.A4.getHeight() - 100 - (cell / 2) * 89;
                    try (var out = new PDPageContentStream(pdf, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                        out.drawImage(image, x, y, 270, 81);
                    }
                }
            }
            pdf.save(bytes);
            return new Download(bytes.toByteArray());
        }
    }

    private BufferedImage label(Variant v) throws WriterException {
        var canvas = new BufferedImage(900, 270, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 900, 270);
            g.setColor(Color.BLACK);
            BitMatrix qr = new QRCodeWriter().encode(v.id().toString(), BarcodeFormat.QR_CODE, 260, 260);
            for (int y = 0; y < qr.getHeight(); y++) {
                for (int x = 0; x < qr.getWidth(); x++) {
                    if (qr.get(x, y)) canvas.setRGB(x + 5, y + 5, Color.BLACK.getRGB());
                }
            }
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            line(g, v.name(), 72, Font.BOLD, 34);
            line(g, v.size() + (v.color() == null ? "" : " · " + v.color()), 140, Font.PLAIN, 30);
            line(g, v.sku(), 204, Font.PLAIN, 28);
        } finally {
            g.dispose();
        }
        return canvas;
    }

    private void line(Graphics2D g, String text, int baseline, int style, int size) {
        Font font = new Font(Font.SANS_SERIF, style, size);
        g.setFont(font);
        while (g.getFontMetrics().stringWidth(text) > 610 && size > 18) g.setFont(font.deriveFont((float) --size));
        if (g.getFontMetrics().stringWidth(text) > 610) {
            while (g.getFontMetrics().stringWidth(text + "…") > 610) text = text.substring(0, text.offsetByCodePoints(text.length(), -1));
            text += "…";
        }
        g.drawString(text, 275, baseline);
    }
}
