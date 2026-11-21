CREATE TABLE beta_company_registration (
    tenant_id UUID PRIMARY KEY REFERENCES tenant_fiscal_issuer(tenant_id),
    registered_ruc VARCHAR(11) NOT NULL,
    company_id BIGINT UNIQUE CHECK (company_id > 0),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
