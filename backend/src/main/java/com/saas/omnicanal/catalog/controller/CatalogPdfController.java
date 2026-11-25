package com.saas.omnicanal.catalog.controller;

import static com.saas.omnicanal.shared.security.OwnerAccess.requireOwner;

import com.saas.omnicanal.catalog.service.CatalogPdfService;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Catálogo PDF por categoría: descarga y uso como recurso que comparte la IA. */
@RestController
@RequestMapping("/api/owner/stores/{store}/catalog")
class CatalogPdfController {
    private final CatalogPdfService pdfs;

    CatalogPdfController(CatalogPdfService pdfs) {
        this.pdfs = pdfs;
    }

    @GetMapping("/pdf")
    ResponseEntity<byte[]> download(@PathVariable UUID store, Principal owner, @RequestParam String category) {
        requireOwner(store, owner);
        var pdf = pdfs.generate(store, category);
        String name = "catalogo-" + pdf.category().toLowerCase().replaceAll("[^a-z0-9áéíóúñ]+", "-") + ".pdf";
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
                .header("X-Products", String.valueOf(pdf.products()))
                .body(pdf.content());
    }

    @PostMapping("/resources/generated-pdf")
    Map<String, String> useAsResource(@PathVariable UUID store, Principal owner, @RequestParam String category) {
        requireOwner(store, owner);
        return Map.of("pdf", pdfs.saveAsResource(store, category));
    }
}
