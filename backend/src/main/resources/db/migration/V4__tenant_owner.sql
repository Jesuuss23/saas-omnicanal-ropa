CREATE TABLE tenant_owner (
    tenant_id UUID PRIMARY KEY REFERENCES tenant(id),
    email VARCHAR(254) NOT NULL
);
