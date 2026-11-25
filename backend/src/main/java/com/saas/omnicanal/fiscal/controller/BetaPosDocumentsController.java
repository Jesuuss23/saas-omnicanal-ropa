package com.saas.omnicanal.fiscal.controller;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/owner/stores/{store}/billing/pos-documents")
class BetaPosDocumentsController {
    private final JdbcTemplate db;

    BetaPosDocumentsController(JdbcTemplate db) {
        this.db = db;
    }

    record Document(UUID sale, OffsetDateTime created, String kind, BigDecimal total,
                    String customer, String status, String series, Integer number, boolean hasXml, boolean hasCdr,
                    String pdfUrl, Submission submission) {}
    record Submission(OffsetDateTime attemptedAt, String cdrCode, String description, List<String> notes,
                      String errorCode, String errorMessage, Integer httpStatus) {}
    record Page(List<Document> documents, boolean more) {}
    record Numbered(String series, int number) {}

    @GetMapping
    Page list(@PathVariable UUID store, Principal principal,
              @RequestParam(defaultValue="0") int page) {
        requireOwner(store, principal);
        if (page < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var rows = db.query("""
                SELECT f.sale_id, f.created_at, f.kind, s.total, f.customer_name, f.status, n.series, n.number,
                       NULLIF(btrim(n.provider_response->>'xml_zip_base64'),'') IS NOT NULL,
                       NULLIF(btrim(n.provider_response->>'cdr_zip_base64'),'') IS NOT NULL,
                       n.submitted_at, NULLIF(n.provider_response->>'sunat_responsecode','') AS cdr_code,
                       NULLIF(n.provider_response->>'sunat_description','') AS description,
                       ARRAY_REMOVE(ARRAY[NULLIF(n.provider_response->>'sunat_note',''),
                                          NULLIF(n.provider_response->>'sunat_soap_error','')], NULL) AS notes,
                       n.provider_response#>>'{error,codigo}' AS error_code,
                       n.provider_response#>>'{error,errors}' AS error_message,
                       n.provider_response->>'transportHttpStatus' AS transport_status,
                       NULLIF(n.provider_response->>'enlace_del_pdf','') AS pdf_url
                  FROM pos_fiscal_request f JOIN pos_sale s ON (s.tenant_id,s.id)=(f.tenant_id,f.sale_id)
                  LEFT JOIN beta_pos_document_number n ON (n.tenant_id,n.sale_id)=(f.tenant_id,f.sale_id)
                 WHERE f.tenant_id=? ORDER BY f.created_at DESC, f.sale_id DESC LIMIT 21 OFFSET ?
                """,
                (rs,row) -> new Document(rs.getObject(1,UUID.class),rs.getObject(2,OffsetDateTime.class),
                        rs.getString(3),rs.getBigDecimal(4),rs.getString(5),rs.getString(6),
                        rs.getString(7),rs.getObject(8,Integer.class),rs.getBoolean(9),rs.getBoolean(10),
                        pdfUrl(rs.getString("pdf_url")),submission(rs)), store, page * 20L);
        return new Page(rows.subList(0, Math.min(rows.size(),20)), rows.size()>20);
    }

    /** Solo enlaces https de Nubefact (los guarda el proveedor, pero se muestran como enlace en el panel). */
    private static String pdfUrl(String url) {
        return url != null && url.matches("^https://([a-z0-9-]+\\.)*nubefact\\.com/[^\\s\"'<>]*$") ? url : null;
    }

    private static Submission submission(java.sql.ResultSet rs) throws java.sql.SQLException {
        var attemptedAt = rs.getObject("submitted_at", OffsetDateTime.class);
        if (attemptedAt == null) return null;
        String http = rs.getString("transport_status");
        return new Submission(attemptedAt, rs.getString("cdr_code"), rs.getString("description"),
                List.of((String[])rs.getArray("notes").getArray()), rs.getString("error_code"),
                rs.getString("error_message"), http != null && http.matches("[1-5][0-9]{2}") ? Integer.valueOf(http) : null);
    }

    record Download(String filename, byte[] content) {}

    @GetMapping("/{sale}/files/{kind}")
    ResponseEntity<Download> download(@PathVariable UUID store, @PathVariable UUID sale,
                                      @PathVariable String kind, Principal principal) {
        requireOwner(store, principal);
        boolean xml = "xml".equals(kind);
        if (!xml && !"cdr".equals(kind)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        Download file = db.query("""
                SELECT n.issuer_ruc, f.kind, n.series, n.number,
                       CASE WHEN ? THEN n.provider_response->>'xml_zip_base64'
                            ELSE n.provider_response->>'cdr_zip_base64' END AS content
                  FROM beta_pos_document_number n JOIN pos_fiscal_request f USING (tenant_id,sale_id)
                 WHERE n.tenant_id=? AND n.sale_id=?
                """, (rs,row) -> {
                    String stored = rs.getString("content");
                    if (stored == null || stored.isBlank()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
                    String docType = "FACTURA".equals(rs.getString("kind")) ? "01" : "03";
                    String stem = String.join("-", rs.getString("issuer_ruc"),docType,
                            rs.getString("series"),rs.getString("number"));
                    String filename = xml ? stem + ".xml" : "R-" + stem + ".zip";
                    return new Download(filename, fileBytes(stored, xml));
                }, xml,store,sale).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(file);
    }

    /** Nubefact entrega XML y CDR en zip Base64. El XML se descomprime para descargarlo e imprimirlo. */
    private static byte[] fileBytes(String stored, boolean xml) {
        try {
            byte[] bytes = Base64.getDecoder().decode(stored.replaceAll("\\s+", ""));
            if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') throw new IllegalArgumentException();
            if (!xml) return bytes;
            try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    if (!entry.isDirectory() && entry.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".xml")) {
                        byte[] content = zip.readNBytes(5 * 1024 * 1024 + 1);
                        if (content.length == 0 || content.length > 5 * 1024 * 1024) throw new IllegalArgumentException();
                        return content;
                    }
                }
            }
            throw new IllegalArgumentException();
        } catch (IllegalArgumentException | java.io.IOException malformed) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "El archivo guardado de Nubefact tiene un formato inválido.");
        }
    }

    @PostMapping("/{sale}/number")
    @Transactional
    Numbered assign(@PathVariable UUID store, @PathVariable UUID sale, Principal principal) {
        requireOwner(store, principal);
        String kind = db.query("SELECT kind FROM pos_fiscal_request WHERE tenant_id=? AND sale_id=? AND status='PENDING' FOR UPDATE",
                (rs,row) -> rs.getString(1), store,sale).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var previous = db.query("SELECT series,number FROM beta_pos_document_number WHERE tenant_id=? AND sale_id=?",
                (rs,row) -> new Numbered(rs.getString(1),rs.getInt(2)),store,sale);
        if (!previous.isEmpty()) return previous.get(0);

        record Issuer(String ruc, String invoiceSeries, String receiptSeries) {}
        Issuer issuer = db.query("""
                SELECT i.ruc, a.invoice_series, a.receipt_series
                  FROM tenant_fiscal_issuer i JOIN store_billing_account a USING (tenant_id)
                 WHERE i.tenant_id=? FOR SHARE OF i,a
                """, (rs,row) -> new Issuer(rs.getString(1),rs.getString(2),rs.getString(3)),store).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Guarda los datos del emisor y conecta tu cuenta de Nubefact en Facturación."));
        String ruc = issuer.ruc();
        String series = kind.equals("FACTURA") ? issuer.invoiceSeries() : issuer.receiptSeries();
        int number = db.query("""
                INSERT INTO beta_document_counter(issuer_ruc,series,last_number) VALUES (?,?,1)
                ON CONFLICT (issuer_ruc,series) DO UPDATE
                   SET last_number=beta_document_counter.last_number+1
                 WHERE beta_document_counter.last_number<99999999 RETURNING last_number
                """, (rs,row) -> rs.getInt(1),ruc,series).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "La serie " + series + " agotó sus correlativos."));
        db.update("INSERT INTO beta_pos_document_number(tenant_id,sale_id,issuer_ruc,series,number) VALUES (?,?,?,?,?)",
                store,sale,ruc,series,number);
        return new Numbered(series,number);
    }

    private static void requireOwner(UUID store, Principal principal) {
        if (principal == null || !store.toString().equals(principal.getName()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
}
