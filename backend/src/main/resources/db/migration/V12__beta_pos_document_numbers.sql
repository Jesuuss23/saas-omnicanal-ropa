CREATE TABLE beta_document_counter (
    issuer_ruc VARCHAR(11) NOT NULL,
    series VARCHAR(4) NOT NULL CHECK (series IN ('BT01','FT01')),
    last_number INTEGER NOT NULL CHECK (last_number BETWEEN 1 AND 99999999),
    PRIMARY KEY (issuer_ruc, series)
);

CREATE TABLE beta_pos_document_number (
    tenant_id UUID NOT NULL,
    sale_id UUID NOT NULL,
    issuer_ruc VARCHAR(11) NOT NULL,
    series VARCHAR(4) NOT NULL,
    number INTEGER NOT NULL CHECK (number BETWEEN 1 AND 99999999),
    PRIMARY KEY (tenant_id, sale_id),
    UNIQUE (issuer_ruc, series, number),
    FOREIGN KEY (tenant_id, sale_id) REFERENCES pos_fiscal_request(tenant_id, sale_id),
    FOREIGN KEY (issuer_ruc, series) REFERENCES beta_document_counter(issuer_ruc, series)
);
CREATE INDEX ix_pos_fiscal_request_created ON pos_fiscal_request(tenant_id, created_at DESC, sale_id DESC);
