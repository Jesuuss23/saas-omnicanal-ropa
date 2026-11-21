CREATE TABLE pos_fiscal_request (
    tenant_id UUID NOT NULL,
    sale_id UUID NOT NULL,
    kind VARCHAR(8) NOT NULL CHECK (kind IN ('BOLETA', 'FACTURA')),
    document_type VARCHAR(10),
    document_number VARCHAR(20),
    customer_name VARCHAR(200),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, sale_id),
    FOREIGN KEY (tenant_id, sale_id) REFERENCES pos_sale(tenant_id, id),
    CHECK ((document_type IS NULL AND document_number IS NULL AND customer_name IS NULL)
        OR (document_type IS NOT NULL AND document_number IS NOT NULL AND customer_name IS NOT NULL)),
    CHECK (kind <> 'FACTURA' OR (document_type IS NOT NULL AND document_type = 'RUC'))
);
