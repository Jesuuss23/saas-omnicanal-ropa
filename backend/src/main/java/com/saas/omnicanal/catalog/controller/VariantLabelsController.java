package com.saas.omnicanal.catalog.controller;

import static com.saas.omnicanal.shared.security.OwnerAccess.requireOwner;

import com.saas.omnicanal.catalog.model.ProductCard;
import com.saas.omnicanal.catalog.service.ProductCatalogService;
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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Etiquetas para colgar en la prenda: un QR por producto. En caja se elige la talla y el color al escanearlo. */
@RestController
@RequestMapping("/api/owner/stores/{store}/catalog/labels")
class VariantLabelsController {
    private final ProductCatalogService catalog;

    VariantLabelsController(ProductCatalogService catalog) {
        this.catalog = catalog;
    }

    record Batch(@NotEmpty @Size(max=100) List<@NotNull UUID> products) {}
    record Download(byte[] pdf, int labels) {}

    @PostMapping
    Download generate(@PathVariable UUID store, Principal owner, @Valid @RequestBody Batch batch)
            throws IOException, WriterException {
        requireOwner(store, owner);
        var ids = batch.products().stream().distinct().toList();
        List<ProductCard> cards = catalog.cards(store, ids);
        if (cards.size() != ids.size()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Algún producto ya no existe");

        try (var pdf = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            PDPage page = null;
            for (int index = 0; index < cards.size(); index++) {
                int cell = index % 18;
                if (cell == 0) {
                    page = new PDPage(PDRectangle.A4);
                    pdf.addPage(page);
                }
                var image = LosslessFactory.createFromImage(pdf, label(cards.get(index)));
                float x = 18 + (cell % 2) * 280;
                float y = PDRectangle.A4.getHeight() - 100 - (cell / 2) * 89;
                try (var out = new PDPageContentStream(pdf, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    out.drawImage(image, x, y, 270, 81);
                }
            }
            pdf.save(bytes);
            return new Download(bytes.toByteArray(), cards.size());
        }
    }

    private BufferedImage label(ProductCard p) throws WriterException {
        var canvas = new BufferedImage(900, 270, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 900, 270);
            g.setColor(Color.BLACK);
            BitMatrix qr = new QRCodeWriter().encode(p.id().toString(), BarcodeFormat.QR_CODE, 260, 260);
            for (int y = 0; y < qr.getHeight(); y++) {
                for (int x = 0; x < qr.getWidth(); x++) {
                    if (qr.get(x, y)) canvas.setRGB(x + 5, y + 5, Color.BLACK.getRGB());
                }
            }
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            line(g, p.name(), 72, Font.BOLD, 34);
            line(g, "Tallas: " + join(p, ProductCard.Option::size), 140, Font.PLAIN, 28);
            line(g, "Colores: " + join(p, ProductCard.Option::color), 204, Font.PLAIN, 28);
        } finally {
            g.dispose();
        }
        return canvas;
    }

    private static String join(ProductCard p, java.util.function.Function<ProductCard.Option, String> field) {
        return String.join(" · ", p.variants().stream().map(field).filter(Objects::nonNull).distinct().toList());
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
