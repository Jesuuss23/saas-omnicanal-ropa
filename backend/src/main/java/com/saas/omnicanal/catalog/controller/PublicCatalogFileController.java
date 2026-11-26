package com.saas.omnicanal.catalog.controller;

import java.util.UUID;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
class PublicCatalogFileController {
    private final JdbcTemplate db;

    PublicCatalogFileController(JdbcTemplate db) { this.db=db; }

    /** {@code content} es null cuando el archivo está en Cloudinary: entonces {@code url} es su secure_url. */
    record FileData(String mediaType,byte[] content,String url) {}

    @GetMapping("/api/public/catalog-files/{id}")
    ResponseEntity<FileData> read(@PathVariable UUID id) {
        // Solo se sirve el archivo que la categoría comparte hoy (elegido, o el primero en automático).
        FileData file=db.query("SELECT f.media_type,f.content,f.storage_url FROM category_catalog_file f"
                + " JOIN category_catalog_resource r USING(tenant_id,category)"
                + " WHERE f.id=? AND (" + com.saas.omnicanal.catalog.repository.CategoryResourceRepository.EFFECTIVE_KIND + ")=f.kind"
                + " AND '/catalogos/' || f.id::text = CASE WHEN f.kind='PDF' THEN r.pdf_url ELSE r.image_url END",(rs,row)->new FileData(rs.getString(1),rs.getBytes(2),rs.getString(3)),id).stream().findFirst()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(file);
    }
}
