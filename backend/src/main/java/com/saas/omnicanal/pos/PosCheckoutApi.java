package com.saas.omnicanal.pos;

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
public class PosCheckoutApi {
    private final JdbcTemplate db;
    PosCheckoutApi(JdbcTemplate db) { this.db = db; }

    enum Method { CASH, YAPE, PLIN, CARD }
    record Line(@NotNull UUID id, @Min(1) @Max(10000) int quantity,
                @NotNull @Positive @Digits(integer=8, fraction=2) BigDecimal price) {}
    record Checkout(@NotNull UUID id, @NotNull Method method, @Size(max=100) String reference,
                    @Positive @Digits(integer=12,fraction=2) BigDecimal cashReceived,
                    @NotEmpty @Size(max=100) List<@Valid @NotNull Line> lines) {}
    record Receipt(UUID id, BigDecimal total, BigDecimal change) {}

    @PostMapping
    @Transactional
    public Receipt checkout(@PathVariable UUID store, Principal principal, @Valid @RequestBody Checkout request) {
        if (!store.toString().equals(principal.getName())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var lines = request.lines().stream().sorted(Comparator.comparing(Line::id)).toList();
        if (lines.stream().map(Line::id).distinct().count() != lines.size()) throw invalid();
        BigDecimal total = lines.stream().map(line -> line.price().multiply(BigDecimal.valueOf(line.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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
        if (created == 0) {
            String original = db.queryForObject("SELECT request_signature FROM pos_sale WHERE tenant_id=? AND id=?",
                    String.class, store, request.id());
            if (!signature.toString().equals(original)) throw new ResponseStatusException(HttpStatus.CONFLICT);
        } else {
            for (Line line : lines) recordLine(store, request.id(), line);
        }
        return new Receipt(request.id(), total, cash ? received.subtract(total) : BigDecimal.ZERO);
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
