package com.saas.omnicanal.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/owner/stores/{store}/billing/pos-documents")
class BetaPosEmission {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ApisPeruCompanyDirectory provider;
    private final EmissionTokenCipher cipher;

    BetaPosEmission(JdbcTemplate db, TransactionTemplate tx, ApisPeruCompanyDirectory provider, EmissionTokenCipher cipher) {
        this.db=db;
        this.tx=tx;
        this.provider=provider;
        this.cipher=cipher;
    }

    record Result(String status) {}
    private record Sale(String ruc,String issuer,String address,String ubigeo,String series,int number,
                        String kind,String doc,String buyerId,String buyer,String date,BigDecimal total,
                        long company,byte[] encrypted) {}
    private record Line(String sku,String name,int quantity,BigDecimal price) {}

    @PostMapping("/{sale}/send")
    Result send(@PathVariable UUID store,@PathVariable UUID sale,Principal owner) {
        if (owner == null || !store.toString().equals(owner.getName()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        String existing = db.query("SELECT status FROM pos_fiscal_request WHERE tenant_id=? AND sale_id=?",
                (rs,row)->rs.getString(1),store,sale).stream().findFirst()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"PENDING".equals(existing)) return new Result(existing);
        Sale saved = db.query("""
                SELECT n.issuer_ruc,i.legal_name,i.address,i.ubigeo,n.series,n.number,f.kind,
                       f.document_type,f.document_number,f.customer_name,s.created_at,s.total,
                       b.company_id,b.emission_token_ciphertext
                  FROM beta_pos_document_number n JOIN pos_fiscal_request f USING (tenant_id,sale_id)
                  JOIN pos_sale s ON s.tenant_id=n.tenant_id AND s.id=n.sale_id
                  JOIN tenant_fiscal_issuer i ON i.tenant_id=n.tenant_id AND i.ruc=n.issuer_ruc
                  JOIN beta_company_registration b ON b.tenant_id=i.tenant_id AND b.registered_ruc=i.ruc
                 WHERE n.tenant_id=? AND n.sale_id=? AND b.company_id IS NOT NULL
                """,(rs,row)->new Sale(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                rs.getString(5),rs.getInt(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),
                rs.getObject(11,OffsetDateTime.class).atZoneSameInstant(ZoneId.of("America/Lima")).toOffsetDateTime().toString(),
                rs.getBigDecimal(12),rs.getLong(13),rs.getBytes(14)),store,sale).stream().findFirst()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT));
        String token=cipher.decrypt(store,saved.company(),saved.ruc(),saved.encrypted());
        try {
            provider.requireBetaCompany(saved.company(),saved.ruc(),token);
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        }
        var lines=db.query("""
                SELECT sku,name,quantity,unit_price FROM pos_sale_line
                 WHERE tenant_id=? AND sale_id=? ORDER BY variant_id
                """,(rs,row)->new Line(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getBigDecimal(4)),store,sale);
        var invoice=invoice(saved,lines);
        // Commit before dispatch. A process crash or a lost response must never allow an automatic resend.
        boolean claimed=Boolean.TRUE.equals(tx.execute(t->{
            int changed=db.update("UPDATE pos_fiscal_request SET status='BETA_REVIEW' WHERE tenant_id=? AND sale_id=? AND status='PENDING'",store,sale);
            if (changed==1) db.update("UPDATE beta_pos_document_number SET submitted_at=now() WHERE tenant_id=? AND sale_id=?",store,sale);
            return changed==1;
        }));
        if (!claimed) return new Result(db.queryForObject("SELECT status FROM pos_fiscal_request WHERE tenant_id=? AND sale_id=?",String.class,store,sale));
        JsonNode reply;
        try {
            reply=provider.sendInvoice(invoice,token);
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            db.update("""
                    UPDATE beta_pos_document_number
                       SET provider_response=jsonb_build_object('transportHttpStatus',CAST(? AS integer))
                     WHERE tenant_id=? AND sale_id=? AND provider_response IS NULL
                    """, ex.getStatusCode().value(), store, sale);
            return new Result("BETA_REVIEW");
        } catch (RestClientException ex) {
            return new Result("BETA_REVIEW");
        }
        if (reply==null) return new Result("BETA_REVIEW");
        var sunat=reply.path("sunatResponse");
        var cdr=sunat.path("cdrResponse");
        String status="BETA_REVIEW";
        if (sunat.path("success").asBoolean(false) && cdr.path("accepted").isBoolean()
                && !sunat.path("cdrZip").asText("").isBlank() && !reply.path("xml").asText("").isBlank())
            status=cdr.path("accepted").asBoolean() ? "BETA_ACCEPTED" : "BETA_REJECTED";
        String outcome=status;
        return tx.execute(t->{
            String current = lockStatus(store,sale);
            if (!"BETA_REVIEW".equals(current)) {
                // A concurrent status lookup already supplied the CDR. Keep it and add the original XML/hash.
                db.update("""
                        UPDATE beta_pos_document_number SET provider_response =
                            (CAST(? AS jsonb) - 'sunatResponse') || provider_response
                         WHERE tenant_id=? AND sale_id=?
                        """,reply.toString(),store,sale);
                return new Result(current);
            }
            db.update("UPDATE beta_pos_document_number SET provider_response=CAST(? AS jsonb) WHERE tenant_id=? AND sale_id=?",reply.toString(),store,sale);
            db.update("UPDATE pos_fiscal_request SET status=? WHERE tenant_id=? AND sale_id=?",outcome,store,sale);
            return new Result(outcome);
        });
    }

    private String lockStatus(UUID store, UUID sale) {
        return db.queryForObject("SELECT status FROM pos_fiscal_request WHERE tenant_id=? AND sale_id=? FOR UPDATE",
                String.class,store,sale);
    }

    private record Attempt(String status, String ruc, String kind, String series, int number, long company, byte[] encrypted) {}

    @PostMapping("/{sale}/reconcile")
    Result reconcile(@PathVariable UUID store, @PathVariable UUID sale, Principal owner) {
        if (owner == null || !store.toString().equals(owner.getName()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        Attempt attempt = db.query("""
                SELECT f.status,n.issuer_ruc,f.kind,n.series,n.number,b.company_id,b.emission_token_ciphertext
                  FROM pos_fiscal_request f JOIN beta_pos_document_number n USING (tenant_id,sale_id)
                  JOIN beta_company_registration b ON b.tenant_id=n.tenant_id AND b.registered_ruc=n.issuer_ruc
                 WHERE f.tenant_id=? AND f.sale_id=? AND n.submitted_at IS NOT NULL AND b.company_id IS NOT NULL
                """, (rs,row)->new Attempt(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),
                        rs.getInt(5),rs.getLong(6),rs.getBytes(7)),store,sale).stream().findFirst()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!"BETA_REVIEW".equals(attempt.status())) return new Result(attempt.status());
        String token = cipher.decrypt(store,attempt.company(),attempt.ruc(),attempt.encrypted());
        JsonNode reply;
        try {
            provider.requireBetaCompany(attempt.company(),attempt.ruc(),token);
            String type = "FACTURA".equals(attempt.kind()) ? "01" : "03";
            reply = provider.invoiceStatus(attempt.ruc(),type,attempt.series(),attempt.number(),token);
        } catch (RestClientException unavailable) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        }
        if (reply == null || !reply.path("success").asBoolean(false)
                || !reply.path("cdrResponse").path("accepted").isBoolean()
                || reply.path("cdrZip").asText("").isBlank()) return new Result("BETA_REVIEW");
        String outcome = reply.path("cdrResponse").path("accepted").asBoolean() ? "BETA_ACCEPTED" : "BETA_REJECTED";
        return tx.execute(t -> {
            String current = lockStatus(store,sale);
            if (!"BETA_REVIEW".equals(current)) return new Result(current);
            db.update("""
                    UPDATE beta_pos_document_number SET provider_response =
                        COALESCE(provider_response,'{}'::jsonb) || jsonb_build_object('sunatResponse',CAST(? AS jsonb))
                     WHERE tenant_id=? AND sale_id=?
                    """,reply.toString(),store,sale);
            db.update("UPDATE pos_fiscal_request SET status=? WHERE tenant_id=? AND sale_id=?",outcome,store,sale);
            return new Result(outcome);
        });
    }

    private static Map<String,Object> invoice(Sale s,List<Line> lines) {
        var details=new java.util.ArrayList<Map<String,Object>>();
        BigDecimal net=BigDecimal.ZERO, gross=BigDecimal.ZERO;
        BigDecimal factor=new BigDecimal("1.18");
        for (Line line:lines) {
            BigDecimal amount=line.price().multiply(BigDecimal.valueOf(line.quantity()));
            BigDecimal base=amount.divide(factor,2,RoundingMode.HALF_UP);
            BigDecimal tax=amount.subtract(base);
            net=net.add(base);
            gross=gross.add(amount);
            var detail=new LinkedHashMap<String,Object>();
            detail.put("codProducto",line.sku()); detail.put("descripcion",line.name()); detail.put("unidad","NIU");
            detail.put("cantidad",line.quantity()); detail.put("mtoPrecioUnitario",line.price());
            detail.put("mtoValorUnitario",line.price().divide(factor,10,RoundingMode.HALF_UP));
            detail.put("mtoValorVenta",base); detail.put("mtoBaseIgv",base); detail.put("igv",tax);
            detail.put("totalImpuestos",tax); detail.put("porcentajeIgv",18); detail.put("tipAfeIgv",10);
            details.add(detail);
        }
        if (lines.isEmpty() || gross.compareTo(s.total())!=0) throw new ResponseStatusException(HttpStatus.CONFLICT);
        var payload=new LinkedHashMap<String,Object>();
        payload.put("ublVersion","2.1"); payload.put("tipoOperacion","0101"); payload.put("tipoMoneda","PEN");
        payload.put("tipoDoc","FACTURA".equals(s.kind())?"01":"03"); payload.put("serie",s.series());
        payload.put("correlativo",String.valueOf(s.number())); payload.put("fechaEmision",s.date());
        payload.put("formaPago",Map.of("moneda","PEN","tipo","Contado"));
        String type=s.doc()==null?"0":Map.of("RUC","6","DNI","1","CE","4","PASSPORT","7").get(s.doc());
        payload.put("client",Map.of("tipoDoc",type,"numDoc",s.buyerId()==null?"-":s.buyerId(),"rznSocial",s.buyer()==null?"CLIENTE VARIOS":s.buyer()));
        payload.put("company",Map.of("ruc",s.ruc(),"razonSocial",s.issuer(),"address",Map.of("direccion",s.address(),"ubigueo",s.ubigeo(),"codigoPais","PE")));
        payload.put("details",details); payload.put("mtoOperGravadas",net); payload.put("valorVenta",net);
        payload.put("mtoIGV",gross.subtract(net)); payload.put("totalImpuestos",gross.subtract(net));
        payload.put("subTotal",gross); payload.put("mtoImpVenta",gross);
        return payload;
    }
}
