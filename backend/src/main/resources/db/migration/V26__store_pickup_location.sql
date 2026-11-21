ALTER TABLE tenant
    ADD COLUMN pickup_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN pickup_address VARCHAR(250) NOT NULL DEFAULT '',
    ADD COLUMN pickup_hours VARCHAR(160) NOT NULL DEFAULT '',
    ADD COLUMN pickup_revision BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_tenant_pickup_location CHECK (
        NOT pickup_enabled OR (length(trim(pickup_address)) > 0 AND length(trim(pickup_hours)) > 0)
    );
