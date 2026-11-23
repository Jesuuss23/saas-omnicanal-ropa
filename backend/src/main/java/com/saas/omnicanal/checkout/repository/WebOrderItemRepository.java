package com.saas.omnicanal.checkout.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WebOrderItemRepository {
    private final JdbcTemplate db;

    WebOrderItemRepository(JdbcTemplate db) { this.db = db; }

    public record Item(UUID variant, String name, String size, String color, int quantity, BigDecimal unitPrice) {}

    public List<Item> list(UUID store, UUID order) {
        return db.query("""
                SELECT variant_id,name,size,color,quantity,unit_price
                FROM web_order_item WHERE tenant_id=? AND order_id=?
                ORDER BY name,size,color,variant_id
                """,(rs,n)->new Item(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getInt(5),rs.getBigDecimal(6)),store,order);
    }
}
