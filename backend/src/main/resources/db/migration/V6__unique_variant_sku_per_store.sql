CREATE UNIQUE INDEX uq_product_variant_store_sku
    ON product_variant (tenant_id, upper(trim(sku)));
