ALTER TABLE tenant
    ADD COLUMN dispatch_lat NUMERIC(9,6) CHECK (dispatch_lat BETWEEN -90 AND 90),
    ADD COLUMN dispatch_lon NUMERIC(9,6) CHECK (dispatch_lon BETWEEN -180 AND 180),
    ADD COLUMN delivery_per_km NUMERIC(8,2) CHECK (delivery_per_km > 0),
    ADD CHECK (num_nonnulls(dispatch_lat,dispatch_lon,delivery_per_km) IN (0,3));
ALTER TABLE web_checkout_order
    ADD COLUMN delivery_fee NUMERIC(14,2) CHECK (delivery_fee>=0),
    ADD COLUMN destination_lat NUMERIC(9,6) CHECK (destination_lat BETWEEN -90 AND 90),
    ADD COLUMN destination_lon NUMERIC(9,6) CHECK (destination_lon BETWEEN -180 AND 180);
