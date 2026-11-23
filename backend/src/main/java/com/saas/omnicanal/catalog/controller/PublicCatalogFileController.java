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

    record FileData(String mediaType,byte[] content) {}

    @GetMapping("/api/public/catalog-files/{id}")
    ResponseEntity<FileData> read(@PathVariable UUID id) {
        FileData file=db.query("""
                SELECT f.media_type,f.content FROM category_catalog_file f
                  JOIN category_catalog_resource r USING(tenant_id,category)
                 WHERE f.id=? AND r.active=f.kind AND '/catalogos/' || f.id::text =
                       CASE WHEN f.kind='PDF' THEN r.pdf_url ELSE r.image_url END
                """,(rs,row)->new FileData(rs.getString(1),rs.getBytes(2)),id).stream().findFirst()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(file);
    }
}
