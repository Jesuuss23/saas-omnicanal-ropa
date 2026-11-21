CREATE TABLE web_order_item (
    tenant_id UUID NOT NULL,
    order_id UUID NOT NULL,
    variant_id UUID NOT NULL,
    name VARCHAR(150) NOT NULL,
    size VARCHAR(10) NOT NULL,
    color VARCHAR(30),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(10,2) NOT NULL CHECK (unit_price > 0),
    PRIMARY KEY (tenant_id,order_id,variant_id),
    FOREIGN KEY (tenant_id,order_id) REFERENCES web_checkout_order(tenant_id,id),
    FOREIGN KEY (tenant_id,variant_id) REFERENCES product_variant(tenant_id,id)
);

CREATE FUNCTION snapshot_web_order_items() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO web_order_item(tenant_id,order_id,variant_id,name,size,color,quantity,unit_price)
    SELECT NEW.tenant_id,NEW.id,l.variant_id,p.name,v.size,v.color,l.quantity,l.unit_price
    FROM cart_reservation_line l
    JOIN product_variant v ON (v.tenant_id,v.id)=(l.tenant_id,l.variant_id)
    JOIN product p ON (p.tenant_id,p.id)=(v.tenant_id,v.product_id)
    WHERE l.tenant_id=NEW.tenant_id AND l.reservation_id=NEW.reservation_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Pedido sin prendas reservadas';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER web_order_items_on_insert
AFTER INSERT ON web_checkout_order
FOR EACH ROW EXECUTE FUNCTION snapshot_web_order_items();

-- Existing orders only have the product descriptions available at migration time.
INSERT INTO web_order_item(tenant_id,order_id,variant_id,name,size,color,quantity,unit_price)
SELECT o.tenant_id,o.id,l.variant_id,p.name,v.size,v.color,l.quantity,l.unit_price
FROM web_checkout_order o
JOIN cart_reservation_line l ON (l.tenant_id,l.reservation_id)=(o.tenant_id,o.reservation_id)
JOIN product_variant v ON (v.tenant_id,v.id)=(l.tenant_id,l.variant_id)
JOIN product p ON (p.tenant_id,p.id)=(v.tenant_id,v.product_id);
