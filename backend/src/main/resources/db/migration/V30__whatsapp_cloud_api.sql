-- WhatsApp: dos proveedores por tienda (Evolution API por QR y WhatsApp Cloud API oficial).
-- Los secretos de Meta se guardan cifrados (AES-GCM) desde el backend; nunca en texto plano.

CREATE TABLE whatsapp_channel_setting (
    tenant_id UUID PRIMARY KEY REFERENCES tenant(id),
    active_provider VARCHAR(16) NOT NULL DEFAULT 'EVOLUTION'
        CHECK (active_provider IN ('EVOLUTION','CLOUD_API')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE whatsapp_cloud_credential (
    tenant_id UUID PRIMARY KEY REFERENCES tenant(id),
    -- phone_number_id enruta cada webhook de Meta a una sola tienda.
    phone_number_id VARCHAR(32) NOT NULL UNIQUE CHECK (phone_number_id ~ '^[0-9]{5,32}$'),
    waba_id VARCHAR(32) NOT NULL CHECK (waba_id ~ '^[0-9]{5,32}$'),
    access_token_ciphertext BYTEA NOT NULL,
    app_secret_ciphertext BYTEA,
    display_phone_number VARCHAR(32),
    verified_name VARCHAR(200),
    quality_rating VARCHAR(16),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','VERIFIED','INVALID')),
    last_error VARCHAR(500),
    verified_at TIMESTAMPTZ,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE whatsapp_cloud_template (
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    template_id VARCHAR(64) NOT NULL,
    name VARCHAR(512) NOT NULL,
    language VARCHAR(16) NOT NULL,
    category VARCHAR(32),
    status VARCHAR(32) NOT NULL,
    components JSONB NOT NULL DEFAULT '[]'::jsonb,
    synced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, template_id)
);
CREATE INDEX ix_whatsapp_cloud_template_name ON whatsapp_cloud_template (tenant_id, name, language);

-- Registro de mensajes de prueba enviados desde el panel (no forma parte del inbox).
CREATE TABLE whatsapp_cloud_test_message (
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    recipient VARCHAR(16) NOT NULL CHECK (recipient ~ '^[0-9]{8,15}$'),
    kind VARCHAR(8) NOT NULL CHECK (kind IN ('TEXT','TEMPLATE')),
    template_name VARCHAR(512),
    status VARCHAR(12) NOT NULL CHECK (status IN ('ACCEPTED','REJECTED','UNKNOWN')),
    provider_message_id VARCHAR(200),
    error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id)
);
CREATE INDEX ix_whatsapp_cloud_test_recent ON whatsapp_cloud_test_message (tenant_id, created_at DESC);

-- Historial unificado: el mismo chat_id (E.164@s.whatsapp.net) para ambos proveedores,
-- así una tienda migra de QR a Cloud API sin perder conversaciones.
ALTER TABLE whatsapp_inbound_message
    ADD COLUMN provider VARCHAR(16) NOT NULL DEFAULT 'EVOLUTION'
        CHECK (provider IN ('EVOLUTION','CLOUD_API'));
ALTER TABLE whatsapp_manual_reply
    ADD COLUMN provider VARCHAR(16) NOT NULL DEFAULT 'EVOLUTION'
        CHECK (provider IN ('EVOLUTION','CLOUD_API'));
