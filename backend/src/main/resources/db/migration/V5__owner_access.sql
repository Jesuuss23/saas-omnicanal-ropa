ALTER TABLE tenant_owner
    ADD COLUMN password_hash VARCHAR(255),
    ADD COLUMN invitation_hash CHAR(64),
    ADD COLUMN invitation_expires_at TIMESTAMPTZ;
