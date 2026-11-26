package com.saas.omnicanal.catalog.controller;

import com.saas.omnicanal.catalog.repository.CategoryResourceRepository;
import com.saas.omnicanal.shared.storage.MediaStorage;
import com.saas.omnicanal.shared.storage.StoredFile;
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
class CategoryResourceController {
    private final JdbcTemplate db;
    private final MediaStorage storage;
    private final CategoryResourceRepository resources;

    CategoryResourceController(JdbcTemplate db, MediaStorage storage, CategoryResourceRepository resources) {
        this.db = db;
        this.storage = storage;
        this.resources = resources;
    }

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

    @org.springframework.transaction.annotation.Transactional
    @PutMapping
    Resource save(@PathVariable UUID store, Principal owner,@Valid @RequestBody Resource input) {
        requireOwner(store,owner);
        String link=url(input.link()), pdf=resourceUrl(store,input.category(),Kind.PDF,input.pdf()),
                image=resourceUrl(store,input.category(),Kind.IMAGE,input.image());
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
        // Los archivos que dejó de usar la categoría se borran de la base y, al confirmar, de Cloudinary.
        db.query("""
                DELETE FROM category_catalog_file WHERE tenant_id=? AND category=? AND
                ('/catalogos/' || id::text) IS DISTINCT FROM (CASE WHEN kind='PDF' THEN CAST(? AS text) ELSE CAST(? AS text) END)
                RETURNING storage_public_id, storage_resource_type
                """,(rs,row)->new String[]{rs.getString(1),rs.getString(2)},store,input.category(),pdf,image)
                .forEach(f->storage.deleteAfterCommit(f[0],f[1]));
        return new Resource(input.category(),link,pdf,image,input.active(),input.revision()+1);
    }

    record Upload(@NotNull @Valid Resource resource, String pdfData, String imageData) {}

    @PutMapping("/files")
    @org.springframework.transaction.annotation.Transactional
    Resource upload(@PathVariable UUID store, Principal owner, @Valid @RequestBody Upload input) {
        requireOwner(store,owner);
        Resource r=input.resource();
        if (input.pdfData()==null && input.imageData()==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        // Save the category revision first; validation or file failures roll back this entire edit.
        Resource saved=save(store,owner,new Resource(r.category(),r.link(),
                input.pdfData()==null?r.pdf():null,input.imageData()==null?r.image():null,Kind.NONE,r.revision()));
        String pdf=input.pdfData()==null?saved.pdf():writeFile(store,r.category(),Kind.PDF,input.pdfData());
        String image=input.imageData()==null?saved.image():writeFile(store,r.category(),Kind.IMAGE,input.imageData());
        if ((r.active()==Kind.PDF && pdf==null) || (r.active()==Kind.IMAGE && image==null) ||
                (r.active()==Kind.LINK && saved.link()==null)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        db.update("UPDATE category_catalog_resource SET pdf_url=?,image_url=?,active=? WHERE tenant_id=? AND category=?",
                pdf,image,r.active().name(),store,r.category());
        return new Resource(r.category(),saved.link(),pdf,image,r.active(),saved.revision());
    }

    private String writeFile(UUID store,String category,Kind kind,String data) {
        // PDF de catálogo hasta 10 MB; imagen hasta 2 MB.
        int max=kind==Kind.PDF?10*1024*1024:2*1024*1024;
        if (data.length()>(max+2)/3*4+4) throw new ResponseStatusException(HttpStatus.valueOf(413));
        byte[] bytes;
        try { bytes=java.util.Base64.getDecoder().decode(data); }
        catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
        if (bytes.length>max) throw new ResponseStatusException(HttpStatus.valueOf(413));
        String signature=java.util.HexFormat.of().formatHex(bytes,0,Math.min(bytes.length,8));
        String mime;
        if (kind==Kind.PDF && signature.startsWith("255044462d")) mime="application/pdf";
        else if (kind==Kind.IMAGE && signature.equals("89504e470d0a1a0a")) mime="image/png";
        else if (kind==Kind.IMAGE && signature.startsWith("ffd8ff")) mime="image/jpeg";
        else throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        UUID id=UUID.randomUUID();
        StoredFile stored=null;
        if (storage.enabled()) {
            try { stored=storage.upload(store,MediaStorage.Folder.CATALOGS,bytes,mime,kind==Kind.PDF?"catalogo.pdf":null); }
            catch (MediaStorage.StorageException e) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"No se pudo guardar el archivo. Inténtalo otra vez.");
            }
            storage.deleteOnRollback(stored);
        }
        resources.insertFile(id,store,category,kind.name(),mime,bytes,stored);
        return "/catalogos/"+id;
    }

    private String resourceUrl(UUID store,String category,Kind kind,String value) {
        if (value!=null && value.startsWith("/catalogos/")) {
            if (Boolean.TRUE.equals(db.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM category_catalog_file WHERE tenant_id=? AND category=? AND kind=?
                    AND '/catalogos/' || id::text=?)
                    """,Boolean.class,store,category,kind.name(),value))) return value;
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return url(value);
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
