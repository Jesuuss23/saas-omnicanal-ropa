package com.saas.omnicanal.billing;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/owner/stores/{store}/billing/pos-documents")
class BetaPosDocumentsApi {
    private final JdbcTemplate db;

    BetaPosDocumentsApi(JdbcTemplate db) {
        this.db = db;
    }

    record Document(UUID sale, OffsetDateTime created, String kind, BigDecimal total,
                    String customer, String status, String series, Integer number) {}
    record Page(List<Document> documents, boolean more) {}
    record Numbered(String series, int number) {}

    @GetMapping
    Page list(@PathVariable UUID store, Principal principal,
              @RequestParam(defaultValue="0") int page) {
        requireOwner(store, principal);
        if (page < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var rows = db.query("""
                SELECT f.sale_id, f.created_at, f.kind, s.total, f.customer_name, f.status, n.series, n.number
                  FROM pos_fiscal_request f JOIN pos_sale s ON (s.tenant_id,s.id)=(f.tenant_id,f.sale_id)
                  LEFT JOIN beta_pos_document_number n ON (n.tenant_id,n.sale_id)=(f.tenant_id,f.sale_id)
                 WHERE f.tenant_id=? ORDER BY f.created_at DESC, f.sale_id DESC LIMIT 21 OFFSET ?
                """,
                (rs,row) -> new Document(rs.getObject(1,UUID.class),rs.getObject(2,OffsetDateTime.class),
                        rs.getString(3),rs.getBigDecimal(4),rs.getString(5),rs.getString(6),
                        rs.getString(7),rs.getObject(8,Integer.class)), store, page * 20L);
        return new Page(rows.subList(0, Math.min(rows.size(),20)), rows.size()>20);
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

        String ruc = db.query("""
                SELECT i.ruc FROM tenant_fiscal_issuer i JOIN beta_company_registration b USING (tenant_id)
                 WHERE i.tenant_id=? AND i.ruc=b.registered_ruc AND b.company_id IS NOT NULL
                   AND b.emission_token_ciphertext IS NOT NULL FOR SHARE OF i,b
                """, (rs,row) -> rs.getString(1),store).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT));
        String series = kind.equals("FACTURA") ? "FT01" : "BT01";
        int number = db.query("""
                INSERT INTO beta_document_counter(issuer_ruc,series,last_number) VALUES (?,?,1)
                ON CONFLICT (issuer_ruc,series) DO UPDATE
                   SET last_number=beta_document_counter.last_number+1
                 WHERE beta_document_counter.last_number<99999999 RETURNING last_number
                """, (rs,row) -> rs.getInt(1),ruc,series).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY));
        db.update("INSERT INTO beta_pos_document_number(tenant_id,sale_id,issuer_ruc,series,number) VALUES (?,?,?,?,?)",
                store,sale,ruc,series,number);
        return new Numbered(series,number);
    }

    private static void requireOwner(UUID store, Principal principal) {
        if (principal == null || !store.toString().equals(principal.getName()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
}
