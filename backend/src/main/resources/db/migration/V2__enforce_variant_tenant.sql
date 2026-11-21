ALTER TABLE product
    ADD CONSTRAINT uq_product_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE product_variant
    ADD CONSTRAINT fk_variant_product_tenant
    FOREIGN KEY (tenant_id, product_id) REFERENCES product (tenant_id, id);
