CREATE TABLE tenant_fiscal_issuer (
    tenant_id UUID PRIMARY KEY REFERENCES tenant(id),
    ruc VARCHAR(11) NOT NULL CHECK (ruc ~ '^[0-9]{11}$'),
    legal_name VARCHAR(200) NOT NULL,
    address VARCHAR(250) NOT NULL,
    ubigeo VARCHAR(6) NOT NULL CHECK (ubigeo ~ '^[0-9]{6}$'),
    revision INTEGER NOT NULL CHECK (revision > 0)
);
