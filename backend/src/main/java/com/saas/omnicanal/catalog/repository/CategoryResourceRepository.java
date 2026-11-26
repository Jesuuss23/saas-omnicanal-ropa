package com.saas.omnicanal.catalog.repository;

import com.saas.omnicanal.shared.storage.StoredFile;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Recurso de catálogo por categoría (PDF, imagen o enlace) y datos para generar el catálogo PDF.
 * Aunque estén los tres cargados se usa uno: el que eligió la tienda, o en automático PDF, luego imagen, luego enlace.
 */
@Repository
public class CategoryResourceRepository {
    /** Tipo efectivo del recurso de una fila de category_catalog_resource (alias r). */
    public static final String EFFECTIVE_KIND = """
            CASE WHEN r.active <> 'NONE' THEN r.active WHEN r.pdf_url IS NOT NULL THEN 'PDF'
                 WHEN r.image_url IS NOT NULL THEN 'IMAGE' WHEN r.link_url IS NOT NULL THEN 'LINK' END""";

    private final JdbcTemplate db;

    public CategoryResourceRepository(JdbcTemplate db) {
        this.db = db;
    }

    /**
     * {@code file}: contenido si el recurso es un archivo subido o generado (url /catalogos/{id}) guardado en la base;
     * {@code storageUrl}: su secure_url si está en Cloudinary. Ambos null si es una URL externa.
     */
    public record Option(String kind, String url, String mediaType, byte[] file, String storageUrl) {
        public boolean hasFile() {
            return file != null || storageUrl != null;
        }
    }

    /** Todos los recursos cargados de la categoría, en orden de preferencia (el elegido primero). */
    public List<Option> options(UUID tenant, String category) {
        return db.query("""
                SELECT o.kind, o.url, f.media_type, f.content, f.storage_url FROM category_catalog_resource r
                 CROSS JOIN LATERAL (VALUES ('PDF', r.pdf_url, 1), ('IMAGE', r.image_url, 2), ('LINK', r.link_url, 3)) o(kind, url, rank)
                  LEFT JOIN category_catalog_file f ON f.tenant_id = r.tenant_id AND '/catalogos/' || f.id::text = o.url
                 WHERE r.tenant_id = ? AND lower(r.category) = lower(?) AND o.url IS NOT NULL
                 ORDER BY (o.kind = r.active) DESC, o.rank
                """, (rs, n) -> new Option(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBytes(4), rs.getString(5)),
                tenant, category);
    }

    /** Categoría tal como está escrita en los productos (la IA puede mandarla en otra capitalización). */
    public Optional<String> category(UUID tenant, String name) {
        return db.queryForList("""
                SELECT DISTINCT trim(category) FROM product WHERE tenant_id = ? AND lower(trim(category)) = lower(trim(?))
                """, String.class, tenant, name).stream().findFirst();
    }

    public record PdfItem(UUID id, String name, String subcategory, BigDecimal minPrice, BigDecimal maxPrice, String sizes,
                          String colors, int available, String imageType, byte[] image, String imageUrl) {}

    /** Productos de la categoría con su portada, rango de precios, tallas y colores (hasta 120). */
    public List<PdfItem> pdfItems(UUID tenant, String category) {
        return db.query("""
                SELECT p.id, p.name, nullif(trim(p.subcategory), ''), min(v.price), max(v.price),
                       string_agg(DISTINCT v.size, ', '), string_agg(DISTINCT v.color, ', '),
                       sum(greatest(0, coalesce(v.stock_physical, 0) - coalesce(v.stock_reserved, 0)))::int,
                       i.media_type, i.content, i.storage_url
                  FROM product p JOIN product_variant v ON (v.tenant_id, v.product_id) = (p.tenant_id, p.id)
                  LEFT JOIN LATERAL (SELECT media_type, content, storage_url FROM product_image x
                                      WHERE x.tenant_id = p.tenant_id AND x.product_id = p.id ORDER BY x.position LIMIT 1) i ON true
                 WHERE p.tenant_id = ? AND p.active AND lower(trim(p.category)) = lower(trim(?))
                 GROUP BY p.id, p.name, p.subcategory, i.media_type, i.content, i.storage_url
                HAVING sum(greatest(0, coalesce(v.stock_physical, 0) - coalesce(v.stock_reserved, 0))) > 0
                 ORDER BY nullif(trim(p.subcategory), '') NULLS LAST, p.name, p.id LIMIT 120
                """, (rs, n) -> new PdfItem(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                rs.getBigDecimal(5), rs.getString(6), rs.getString(7), rs.getInt(8), rs.getString(9), rs.getBytes(10), rs.getString(11)),
                tenant, category);
    }

    public String storeName(UUID tenant) {
        return db.queryForObject("SELECT name FROM tenant WHERE id = ?", String.class, tenant);
    }

    /**
     * Guarda el PDF generado como recurso PDF de la categoría (reemplaza el anterior). Si la categoría no tenía recurso
     * elegido, queda en automático y el PDF pasa a ser el que se comparte.
     */
    public String savePdf(UUID tenant, String category, byte[] pdf) {
        return savePdf(tenant, category, pdf, null);
    }

    /** Como {@link #savePdf(UUID, String, byte[])}; con {@code stored} guarda solo la referencia de Cloudinary. */
    public String savePdf(UUID tenant, String category, byte[] pdf, StoredFile stored) {
        return savePdf(tenant, category, pdf, stored, removed -> { });
    }

    /** {@code removed} recibe los archivos de Cloudinary del PDF anterior, para borrarlos allá cuando se confirme. */
    public String savePdf(UUID tenant, String category, byte[] pdf, StoredFile stored,
                          java.util.function.Consumer<List<StoredRef>> removed) {
        db.update("""
                INSERT INTO category_catalog_resource(tenant_id, category, active, revision) VALUES (?, ?, 'NONE', 1)
                ON CONFLICT (tenant_id, category) DO NOTHING
                """, tenant, category);
        db.query("SELECT 1 FROM category_catalog_resource WHERE tenant_id = ? AND category = ? FOR UPDATE", rs -> {}, tenant, category);
        removed.accept(db.query("""
                DELETE FROM category_catalog_file WHERE tenant_id = ? AND category = ? AND kind = 'PDF'
                RETURNING storage_public_id, storage_resource_type
                """, (rs, n) -> new StoredRef(rs.getString(1), rs.getString(2)), tenant, category)
                .stream().filter(r -> r.publicId() != null).toList());
        UUID id = UUID.randomUUID();
        insertFile(id, tenant, category, "PDF", "application/pdf", pdf, stored);
        String url = "/catalogos/" + id;
        db.update("UPDATE category_catalog_resource SET pdf_url = ?, revision = revision + 1 WHERE tenant_id = ? AND category = ?",
                url, tenant, category);
        return url;
    }

    /** Archivo de catálogo: con {@code stored} solo la referencia de Cloudinary; sin ella, el contenido en la base. */
    public void insertFile(UUID id, UUID tenant, String category, String kind, String mediaType, byte[] content, StoredFile stored) {
        db.update("""
                INSERT INTO category_catalog_file(id, tenant_id, category, kind, media_type, content,
                                                  storage_url, storage_public_id, storage_resource_type, storage_bytes)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, id, tenant, category, kind, mediaType, stored == null ? content : null,
                stored == null ? null : stored.url(), stored == null ? null : stored.publicId(),
                stored == null ? null : stored.resourceType(), stored == null ? null : stored.bytes());
    }

    public record StoredRef(String publicId, String resourceType) {}
}
