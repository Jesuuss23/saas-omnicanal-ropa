package com.saas.omnicanal.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/owner/stores/{store}/catalog/resources")
class CategoryResourceApi {
    private final JdbcTemplate db;

    CategoryResourceApi(JdbcTemplate db) { this.db = db; }

    enum Kind { NONE, LINK, PDF, IMAGE }
    record Resource(@NotBlank @Size(max=50) String category, @Size(max=2048) String link,
                    @Size(max=2048) String pdf, @Size(max=2048) String image, @NotNull Kind active,
                    @NotNull @PositiveOrZero Long revision) {}

    @GetMapping
    List<Resource> list(@PathVariable UUID store, Principal owner) {
        requireOwner(store,owner);
        return db.query("""
                SELECT c.category,r.link_url,r.pdf_url,r.image_url,coalesce(r.active,'NONE'),coalesce(r.revision,0)
                  FROM (SELECT category FROM product WHERE tenant_id=? AND nullif(trim(category),'') IS NOT NULL
                        UNION SELECT category FROM category_catalog_resource WHERE tenant_id=?) c
                  LEFT JOIN category_catalog_resource r ON r.category=c.category AND r.tenant_id=?
                 ORDER BY c.category
                """,(rs,row)->new Resource(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                Kind.valueOf(rs.getString(5)),rs.getLong(6)),store,store,store);
    }

    @PutMapping
    Resource save(@PathVariable UUID store, Principal owner,@Valid @RequestBody Resource input) {
        requireOwner(store,owner);
        String link=url(input.link()), pdf=url(input.pdf()), image=url(input.image());
        if (switch (input.active()) {
            case LINK -> link==null;
            case PDF -> pdf==null;
            case IMAGE -> image==null;
            case NONE -> false;
        }) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        int changed;
        if (input.revision()==0L) {
            changed=db.update("""
                    INSERT INTO category_catalog_resource(tenant_id,category,link_url,pdf_url,image_url,active,revision)
                    SELECT ?,?,?,?,?,?,1 WHERE EXISTS (SELECT 1 FROM product WHERE tenant_id=? AND category=?)
                    ON CONFLICT (tenant_id,category) DO NOTHING
                    """,store,input.category(),link,pdf,image,input.active().name(),store,input.category());
        } else {
            changed=db.update("""
                    UPDATE category_catalog_resource SET link_url=?,pdf_url=?,image_url=?,active=?,revision=revision+1
                     WHERE tenant_id=? AND category=? AND revision=?
                    """,link,pdf,image,input.active().name(),store,input.category(),input.revision());
        }
        if (changed!=1) throw new ResponseStatusException(HttpStatus.CONFLICT);
        return new Resource(input.category(),link,pdf,image,input.active(),input.revision()+1);
    }

    private static String url(String value) {
        if (value==null || value.isBlank()) return null;
        try {
            URI uri=URI.create(value.strip());
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost()!=null && uri.getRawUserInfo()==null)
                return uri.toString();
        } catch (IllegalArgumentException ignored) {}
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }

    private static void requireOwner(UUID store, Principal owner) {
        if (owner==null || !store.toString().equals(owner.getName())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
}
