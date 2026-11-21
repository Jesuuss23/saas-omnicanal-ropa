CREATE TABLE cart_reservation (
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    id UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + interval '15 minutes'),
    released BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (tenant_id, id)
);
CREATE INDEX ix_cart_reservation_expiry ON cart_reservation(expires_at) WHERE NOT released;

ALTER TABLE product_variant ADD CONSTRAINT uq_reservation_variant UNIQUE (tenant_id, id);
CREATE TABLE cart_reservation_line (
    tenant_id UUID NOT NULL,
    reservation_id UUID NOT NULL,
    variant_id UUID NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(10,2) NOT NULL,
    PRIMARY KEY (tenant_id, reservation_id, variant_id),
    FOREIGN KEY (tenant_id, reservation_id) REFERENCES cart_reservation(tenant_id,id),
    FOREIGN KEY (tenant_id, variant_id) REFERENCES product_variant(tenant_id,id)
);
