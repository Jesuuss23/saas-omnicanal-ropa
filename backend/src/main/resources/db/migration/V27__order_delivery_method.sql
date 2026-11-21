ALTER TABLE web_checkout_order
    ADD COLUMN delivery_method VARCHAR(8),
    ADD COLUMN fulfillment_address VARCHAR(250),
    ADD COLUMN pickup_hours VARCHAR(160),
    ADD CONSTRAINT ck_order_delivery_method CHECK (
        CASE WHEN delivery_method IS NULL THEN fulfillment_address IS NULL AND pickup_hours IS NULL
             WHEN delivery_method='DELIVERY' THEN coalesce(length(trim(fulfillment_address)),0)>0 AND pickup_hours IS NULL
             WHEN delivery_method='PICKUP' THEN coalesce(length(trim(fulfillment_address)),0)>0 AND coalesce(length(trim(pickup_hours)),0)>0
             ELSE false END
    );
