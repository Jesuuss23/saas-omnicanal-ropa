CREATE TABLE category_catalog_file (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    category VARCHAR(50) NOT NULL,
    kind VARCHAR(8) NOT NULL CHECK (kind IN ('PDF','IMAGE')),
    media_type VARCHAR(32) NOT NULL,
    content BYTEA NOT NULL CHECK (octet_length(content) BETWEEN 1 AND 2097152),
    UNIQUE (tenant_id,category,kind),
    FOREIGN KEY (tenant_id,category) REFERENCES category_catalog_resource(tenant_id,category)
);
