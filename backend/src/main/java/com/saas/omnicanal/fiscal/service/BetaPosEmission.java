package com.saas.omnicanal.fiscal.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.Principal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/**
 * Emisión de boletas y facturas del POS con el {@link InvoiceProvider} de la tienda (Nubefact).
 * El envío se reclama antes de llamar al proveedor: una respuesta perdida nunca provoca un reenvío automático;
 * el estado se resuelve consultando ({@code reconcile}). Solo se permite reenviar cuando el proveedor confirma que
 * el comprobante no se generó (credenciales o datos inválidos).
 */
@RestController
@RequestMapping("/api/owner/stores/{store}/billing/pos-documents")
public class BetaPosEmission {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final InvoiceProvider provider;
    private final BillingAccountService accounts;

    BetaPosEmission(JdbcTemplate db, TransactionTemplate tx, InvoiceProvider provider, BillingAccountService accounts) {
        this.db = db;
        this.tx = tx;
        this.provider = provider;
        this.accounts = accounts;
    }

    record Result(String status) {}
    private record Sale(String series, int number, String kind, String doc, String buyerId, String buyer,
                        OffsetDateTime date, BigDecimal total) {}
    private record Line(String sku, String name, int quantity, BigDecimal price) {}

    @PostMapping("/{sale}/send")
    Result send(@PathVariable UUID store, @PathVariable UUID sale, Principal owner) {
        requireOwner(store, owner);
        String existing = db.query("SELECT status FROM pos_fiscal_request WHERE tenant_id=? AND sale_id=?",
                (rs, row) -> rs.getString(1), store, sale).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No existe esa solicitud de comprobante."));
        if (!"PENDING".equals(existing)) return new Result(existing);
        Sale saved = db.query("""
                SELECT n.series,n.number,f.kind,f.document_type,f.document_number,f.customer_name,s.created_at,s.total
                  FROM beta_pos_document_number n JOIN pos_fiscal_request f USING (tenant_id,sale_id)
                  JOIN pos_sale s ON s.tenant_id=n.tenant_id AND s.id=n.sale_id
                 WHERE n.tenant_id=? AND n.sale_id=?
                """, (rs, row) -> new Sale(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getObject(7, OffsetDateTime.class), rs.getBigDecimal(8)), store, sale).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Asigna primero el número del comprobante."));
        var active = accounts.active(store);
        var lines = db.query("""
                SELECT sku,name,quantity,unit_price FROM pos_sale_line
                 WHERE tenant_id=? AND sale_id=? ORDER BY variant_id
                """, (rs, row) -> new Line(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getBigDecimal(4)), store, sale);
        var document = document(saved, lines);
        // Commit before dispatch. A process crash or a lost response must never allow an automatic resend.
        boolean claimed = Boolean.TRUE.equals(tx.execute(t -> {
            int changed = db.update("UPDATE pos_fiscal_request SET status='BETA_REVIEW' WHERE tenant_id=? AND sale_id=? AND status='PENDING'", store, sale);
            if (changed == 1) db.update("UPDATE beta_pos_document_number SET submitted_at=now(), provider_response=NULL WHERE tenant_id=? AND sale_id=?", store, sale);
            return changed == 1;
        }));
        if (!claimed) return new Result(currentStatus(store, sale));
        JsonNode reply;
        try {
            reply = provider.send(active.credentials(), document);
        } catch (InvoiceProvider.ProviderException ex) {
            saveError(store, sale, ex);
            if (ex.credentials()) accounts.markError(store, ex.getMessage());
            if (ex.notCreated()) {
                // Nubefact confirmó que no lo generó: se puede corregir y volver a enviar con el mismo número.
                db.update("UPDATE pos_fiscal_request SET status='PENDING' WHERE tenant_id=? AND sale_id=? AND status='BETA_REVIEW'", store, sale);
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
            }
            return new Result("BETA_REVIEW");
        }
        return store(store, sale, reply);
    }

    @PostMapping("/{sale}/reconcile")
    Result reconcile(@PathVariable UUID store, @PathVariable UUID sale, Principal owner) {
        requireOwner(store, owner);
        return reconcileResult(store, sale);
    }

    /** Solo consulta el estado; nunca reenvía. La usa también {@link FiscalReconciler}. */
    String reconcileNow(UUID store, UUID sale) {
        return reconcileResult(store, sale).status();
    }

    private record Attempt(String status, String kind, String series, int number) {}

    private Result reconcileResult(UUID store, UUID sale) {
        Attempt attempt = db.query("""
                SELECT f.status,f.kind,n.series,n.number
                  FROM pos_fiscal_request f JOIN beta_pos_document_number n USING (tenant_id,sale_id)
                 WHERE f.tenant_id=? AND f.sale_id=? AND n.submitted_at IS NOT NULL
                """, (rs, row) -> new Attempt(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)), store, sale)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Este comprobante aún no se envió."));
        if (!"BETA_REVIEW".equals(attempt.status())) return new Result(attempt.status());
        var active = accounts.active(store);
        JsonNode reply;
        try {
            reply = provider.status(active.credentials(), "FACTURA".equals(attempt.kind()) ? 1 : 2, attempt.series(), attempt.number());
        } catch (InvoiceProvider.ProviderException ex) {
            if (ex.credentials()) accounts.markError(store, ex.getMessage());
            // 24: Nubefact no lo tiene. No llegó a generarse, así que puede volver a enviarse.
            if ("24".equals(ex.code())) {
                tx.executeWithoutResult(t -> {
                    if ("BETA_REVIEW".equals(lockStatus(store, sale)))
                        db.update("UPDATE pos_fiscal_request SET status='PENDING' WHERE tenant_id=? AND sale_id=?", store, sale);
                });
                return new Result(currentStatus(store, sale));
            }
            throw new ResponseStatusException(ex.notCreated() ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        }
        return store(store, sale, reply);
    }

    /** ACCEPTED/REJECTED solo con respuesta de SUNAT; cualquier otra cosa queda en revisión para consultarla luego. */
    static String outcome(JsonNode reply) {
        if (reply.path("aceptada_por_sunat").asBoolean(false)) return "BETA_ACCEPTED";
        String code = reply.path("sunat_responsecode").asText("").strip();
        if (!code.isEmpty() && !code.equals("0")) return "BETA_REJECTED";
        return "BETA_REVIEW";
    }

    private Result store(UUID store, UUID sale, JsonNode reply) {
        String outcome = outcome(reply);
        return tx.execute(t -> {
            String current = lockStatus(store, sale);
            db.update("""
                    UPDATE beta_pos_document_number SET provider_response=COALESCE(provider_response,'{}'::jsonb) - 'error' || CAST(? AS jsonb)
                     WHERE tenant_id=? AND sale_id=?
                    """, reply.toString(), store, sale);
            if (!"BETA_REVIEW".equals(current)) return new Result(current);
            db.update("UPDATE pos_fiscal_request SET status=? WHERE tenant_id=? AND sale_id=?", outcome, store, sale);
            return new Result(outcome);
        });
    }

    private void saveError(UUID store, UUID sale, InvoiceProvider.ProviderException ex) {
        db.update("""
                UPDATE beta_pos_document_number SET provider_response=COALESCE(provider_response,'{}'::jsonb) ||
                    jsonb_build_object('error', jsonb_build_object('codigo',CAST(? AS text),'errors',CAST(? AS text)),
                                       'transportHttpStatus', CAST(? AS integer))
                 WHERE tenant_id=? AND sale_id=?
                """, ex.code(), ex.getMessage(), ex.httpStatus(), store, sale);
    }

    private String currentStatus(UUID store, UUID sale) {
        return db.queryForObject("SELECT status FROM pos_fiscal_request WHERE tenant_id=? AND sale_id=?", String.class, store, sale);
    }

    private String lockStatus(UUID store, UUID sale) {
        return db.queryForObject("SELECT status FROM pos_fiscal_request WHERE tenant_id=? AND sale_id=? FOR UPDATE",
                String.class, store, sale);
    }

    private static void requireOwner(UUID store, Principal owner) {
        if (owner == null || !store.toString().equals(owner.getName()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    /** Comprobante en el formato JSON de Nubefact. Precios del POS con IGV incluido (18 %). */
    private static Map<String, Object> document(Sale s, List<Line> lines) {
        var items = new java.util.ArrayList<Map<String, Object>>();
        BigDecimal net = BigDecimal.ZERO, gross = BigDecimal.ZERO;
        BigDecimal factor = new BigDecimal("1.18");
        for (Line line : lines) {
            BigDecimal amount = line.price().multiply(BigDecimal.valueOf(line.quantity()));
            BigDecimal base = amount.divide(factor, 2, RoundingMode.HALF_UP);
            net = net.add(base);
            gross = gross.add(amount);
            var item = new LinkedHashMap<String, Object>();
            item.put("unidad_de_medida", "NIU");
            item.put("codigo", line.sku());
            item.put("descripcion", line.name());
            item.put("cantidad", line.quantity());
            item.put("valor_unitario", line.price().divide(factor, 10, RoundingMode.HALF_UP));
            item.put("precio_unitario", line.price());
            item.put("descuento", "");
            item.put("subtotal", base);
            item.put("tipo_de_igv", 1);
            item.put("igv", amount.subtract(base));
            item.put("total", amount);
            item.put("anticipo_regularizacion", false);
            items.add(item);
        }
        if (lines.isEmpty() || gross.compareTo(s.total()) != 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El detalle de la venta no coincide con su total.");
        String type = s.doc() == null ? "-" : Map.of("RUC", "6", "DNI", "1", "CE", "4", "PASSPORT", "7").getOrDefault(s.doc(), "-");
        var doc = new LinkedHashMap<String, Object>();
        doc.put("tipo_de_comprobante", "FACTURA".equals(s.kind()) ? 1 : 2);
        doc.put("serie", s.series());
        doc.put("numero", s.number());
        doc.put("sunat_transaction", 1);
        doc.put("cliente_tipo_de_documento", type);
        doc.put("cliente_numero_de_documento", s.buyerId() == null ? "00000000" : s.buyerId());
        doc.put("cliente_denominacion", s.buyer() == null ? "CLIENTE VARIOS" : s.buyer());
        doc.put("cliente_direccion", "");
        doc.put("cliente_email", "");
        doc.put("fecha_de_emision", s.date().atZoneSameInstant(ZoneId.of("America/Lima")).format(DATE));
        doc.put("moneda", 1);
        doc.put("porcentaje_de_igv", new BigDecimal("18.00"));
        doc.put("total_gravada", net);
        doc.put("total_igv", gross.subtract(net));
        doc.put("total", gross);
        doc.put("enviar_automaticamente_a_la_sunat", true);
        doc.put("enviar_automaticamente_al_cliente", false);
        doc.put("items", items);
        return doc;
    }
}
