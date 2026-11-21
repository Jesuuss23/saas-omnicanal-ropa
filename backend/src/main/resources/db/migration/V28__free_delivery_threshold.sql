ALTER TABLE tenant ADD COLUMN free_delivery_minimum NUMERIC(10,2)
    CHECK (free_delivery_minimum >= 0);
ALTER TABLE web_checkout_order ADD COLUMN free_delivery BOOLEAN NOT NULL DEFAULT false,
    ADD CHECK (NOT free_delivery OR delivery_method IS NOT DISTINCT FROM 'DELIVERY');
