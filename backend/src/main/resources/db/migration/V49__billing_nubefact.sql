-- Facturación electrónica: cada tienda conecta su propia cuenta de Nubefact (RUTA + TOKEN) (reemplaza al proveedor anterior).

-- Conexión por tienda. El TOKEN va cifrado con SecretCipher (contexto nubefact/token/{tenant}).
CREATE TABLE store_billing_account (
    tenant_id             uuid         PRIMARY KEY REFERENCES tenant (id) ON DELETE CASCADE,
    provider              varchar(20)  NOT NULL DEFAULT 'NUBEFACT' CHECK (provider IN ('NUBEFACT')),
    api_url               varchar(300) NOT NULL CHECK (api_url ~ '^https://[A-Za-z0-9.-]+(:[0-9]+)?/[A-Za-z0-9/_.-]*$'),
    api_token_ciphertext  bytea        NOT NULL,
    environment           varchar(10)  NOT NULL DEFAULT 'BETA' CHECK (environment IN ('BETA', 'PRODUCTION')),
    invoice_series        varchar(4)   NOT NULL CHECK (invoice_series ~ '^F[A-Z0-9]{3}$'),
    receipt_series        varchar(4)   NOT NULL CHECK (receipt_series ~ '^B[A-Z0-9]{3}$'),
    status                varchar(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ERROR')),
    last_error            varchar(500),
    verified_at           timestamptz,
    updated_at            timestamptz  NOT NULL DEFAULT now()
);
COMMENT ON TABLE store_billing_account IS 'Credenciales de Nubefact de cada tienda; api_token_ciphertext cifrado con SECRETS_ENCRYPTION_KEY';

-- Alta de empresas y tokens del proveedor anterior: ya no aplican.
DROP TABLE IF EXISTS beta_company_registration;

-- Las series vienen de la cuenta Nubefact de cada tienda (antes fijas en FT01/BT01).
ALTER TABLE beta_document_counter DROP CONSTRAINT IF EXISTS beta_document_counter_series_check;
ALTER TABLE beta_document_counter ADD CONSTRAINT ck_document_counter_series CHECK (series ~ '^[FB][A-Z0-9]{3}$');
