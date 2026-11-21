CREATE TABLE IF NOT EXISTS tenant (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    domain VARCHAR(100) UNIQUE,
    whatsapp_instance_id VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS product (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    name VARCHAR(150) NOT NULL,
    description TEXT,
    category VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS product_variant (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES product(id),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    sku VARCHAR(50) NOT NULL,
    size VARCHAR(10) NOT NULL,
    color VARCHAR(30),
    price DECIMAL(10,2) NOT NULL,
    stock_physical INT DEFAULT 0,
    stock_reserved INT DEFAULT 0
);

ALTER TABLE product
    ADD CONSTRAINT uq_product_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE product_variant
    ADD CONSTRAINT fk_variant_product_tenant
    FOREIGN KEY (tenant_id, product_id) REFERENCES product (tenant_id, id);
