package com.saas.omnicanal.pos.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/owner/stores/{store}/pos/sales")
public class PosCheckoutController {
    private final JdbcTemplate db;
    PosCheckoutController(JdbcTemplate db) { this.db = db; }

    enum Method { CASH, YAPE, PLIN, CARD }
    record Line(@NotNull UUID id, @Min(1) @Max(10000) int quantity,
                @NotNull @Positive @Digits(integer=8, fraction=2) BigDecimal price) {}
    enum Kind { BOLETA, FACTURA }
    enum DocumentType { DNI, RUC, CE, PASSPORT }
    record Fiscal(@NotNull Kind kind, DocumentType documentType, @Size(max=20) String documentNumber,
                  @Size(max=200) String customerName) {
        Fiscal {
            documentNumber = clean(documentNumber);
            customerName = clean(customerName);
        }
        private static String clean(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }
        void validateFor(BigDecimal total) {
            if (kind == Kind.FACTURA && documentType != DocumentType.RUC) throw invalid();
            if (documentType == null) {
                if (documentNumber != null || customerName != null || total.compareTo(BigDecimal.valueOf(700)) > 0) throw invalid();
                return;
            }
            if (customerName == null || documentNumber == null) throw invalid();
            String pattern = switch (documentType) {
                case DNI -> "[0-9]{8}";
                case RUC -> "[0-9]{11}";
                case CE, PASSPORT -> "[a-zA-Z0-9-]{1,20}";
            };
            if (!documentNumber.matches(pattern)) throw invalid();
        }
    }
    record Checkout(@NotNull UUID id, @NotNull Method method, @Size(max=100) String reference,
                    @Positive @Digits(integer=12,fraction=2) BigDecimal cashReceived,
                    @NotEmpty @Size(max=100) List<@Valid @NotNull Line> lines, @Valid Fiscal fiscal) {}
    record Receipt(UUID id, BigDecimal total, BigDecimal change, String fiscalStatus) {}

    @PostMapping
    @Transactional
    public Receipt checkout(@PathVariable UUID store, Principal principal, @Valid @RequestBody Checkout request) {
        if (!store.toString().equals(principal.getName())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var lines = request.lines().stream().sorted(Comparator.comparing(Line::id)).toList();
        if (lines.stream().map(Line::id).distinct().count() != lines.size()) throw invalid();
        BigDecimal total = lines.stream().map(line -> line.price().multiply(BigDecimal.valueOf(line.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Fiscal fiscal = request.fiscal();
        if (fiscal != null) fiscal.validateFor(total);
        boolean cash = request.method() == Method.CASH;
        String ref = cash || request.reference() == null ? "" : request.reference().strip();
        BigDecimal received = request.cashReceived();
        if (cash ? received == null || received.compareTo(total) < 0 : received != null || ref.isEmpty()) throw invalid();

        // Persist the ordered request identity so an ambiguous network result can be safely retried.
        StringBuilder signature = new StringBuilder();
        for (Line line : lines) {
            signature.append(line.id()).append(':').append(line.quantity()).append(':')
                    .append(line.price().setScale(2)).append('|');
        }
        signature.append(request.method()).append('|').append(cash ? received.setScale(2).toPlainString() : ref);
        int created = db.update("""
                INSERT INTO pos_sale(tenant_id,id,method,reference,total,cash_received,request_signature)
                VALUES (?,?,?,?,?,?,?) ON CONFLICT (tenant_id,id) DO NOTHING
                """, store, request.id(), request.method().name(), cash ? null : ref, total, received, signature.toString());
        String fiscalStatus = null;
        if (created == 0) {
            String original = db.queryForObject("SELECT request_signature FROM pos_sale WHERE tenant_id=? AND id=?",
                    String.class, store, request.id());
            if (!signature.toString().equals(original)) throw new ResponseStatusException(HttpStatus.CONFLICT);
            var saved = db.query("""
                    SELECT kind, document_type, document_number, customer_name, status FROM pos_fiscal_request
                    WHERE tenant_id=? AND sale_id=?
                    """, (rs, row) -> {
                String type = rs.getString("document_type");
                Fiscal previous = new Fiscal(Kind.valueOf(rs.getString("kind")),
                        type == null ? null : DocumentType.valueOf(type), rs.getString("document_number"),
                        rs.getString("customer_name"));
                if (!previous.equals(fiscal)) throw new ResponseStatusException(HttpStatus.CONFLICT);
                return rs.getString("status");
            }, store, request.id());
            if (saved.isEmpty() && fiscal != null) throw new ResponseStatusException(HttpStatus.CONFLICT);
            if (!saved.isEmpty()) fiscalStatus = saved.get(0);
        } else {
            if (fiscal != null) {
                db.update("""
                        INSERT INTO pos_fiscal_request(tenant_id,sale_id,kind,document_type,document_number,customer_name)
                        VALUES (?,?,?,?,?,?)
                        """, store, request.id(), fiscal.kind().name(),
                        fiscal.documentType() == null ? null : fiscal.documentType().name(),
                        fiscal.documentNumber(), fiscal.customerName());
                fiscalStatus = "PENDING";
            }
            for (Line line : lines) recordLine(store, request.id(), line);
        }
        return new Receipt(request.id(), total, cash ? received.subtract(total) : BigDecimal.ZERO, fiscalStatus);
    }

    private void recordLine(UUID store, UUID sale, Line line) {
        int updated = db.update("""
                UPDATE product_variant SET stock_physical = stock_physical - ?
                WHERE tenant_id=? AND id=? AND price=? AND stock_physical - coalesce(stock_reserved,0) >= ?
                """, line.quantity(), store, line.id(), line.price(), line.quantity());
        if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Revisa precio y disponibilidad");
        db.update("""
                INSERT INTO pos_sale_line(tenant_id,sale_id,variant_id,name,sku,size,color,unit_price,quantity)
                SELECT v.tenant_id,?,v.id,p.name,v.sku,v.size,v.color,v.price,?
                FROM product_variant v JOIN product p ON p.id=v.product_id AND p.tenant_id=v.tenant_id
                WHERE v.tenant_id=? AND v.id=?
                """, sale, line.quantity(), store, line.id());
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Datos de cobro inválidos");
    }
}
