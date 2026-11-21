CREATE TABLE pos_sale (
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    method VARCHAR(8) NOT NULL CHECK (method IN ('CASH','YAPE','PLIN','CARD')),
    reference VARCHAR(100),
    total NUMERIC NOT NULL CHECK (total > 0),
    cash_received NUMERIC CHECK (cash_received >= total),
    request_signature TEXT NOT NULL,
    PRIMARY KEY (tenant_id, id)
);

CREATE TABLE pos_sale_line (
    tenant_id UUID NOT NULL,
    sale_id UUID NOT NULL,
    variant_id UUID NOT NULL REFERENCES product_variant(id),
    name VARCHAR(150) NOT NULL,
    sku VARCHAR(50) NOT NULL,
    size VARCHAR(10) NOT NULL,
    color VARCHAR(30),
    unit_price NUMERIC(10,2) NOT NULL CHECK (unit_price > 0),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (tenant_id, sale_id, variant_id),
    FOREIGN KEY (tenant_id, sale_id) REFERENCES pos_sale(tenant_id, id)
);
