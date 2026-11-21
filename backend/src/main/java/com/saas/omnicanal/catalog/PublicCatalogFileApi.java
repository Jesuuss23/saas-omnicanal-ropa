package com.saas.omnicanal.catalog;

import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
class PublicCatalogFileApi {
    private final JdbcTemplate db;

    PublicCatalogFileApi(JdbcTemplate db) { this.db=db; }

    @Bean
    @Order(-1)
    static SecurityFilterChain publicCatalogFiles(HttpSecurity http) throws Exception {
        String guestPath="/api/public/stores/*/reservation/guest";
        return http.securityMatcher("/api/public/catalog-files/*", "/api/public/stores/*/catalog", "/api/public/stores/*/availability", "/api/public/stores/*/reservation", guestPath)
                .csrf(c->c.ignoringRequestMatchers("/api/public/stores/*/reservation", guestPath))
                .authorizeHttpRequests(a->a.requestMatchers(HttpMethod.GET,"/api/public/catalog-files/*", "/api/public/stores/*/catalog", "/api/public/stores/*/availability").permitAll()
                        .requestMatchers(HttpMethod.POST,"/api/public/stores/*/reservation",guestPath).permitAll()
                        .requestMatchers(HttpMethod.DELETE,"/api/public/stores/*/reservation").permitAll()
                        .requestMatchers(HttpMethod.GET,guestPath).permitAll()
                        .requestMatchers(HttpMethod.PUT,guestPath).permitAll()
                        .anyRequest().denyAll()).build();
    }

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
