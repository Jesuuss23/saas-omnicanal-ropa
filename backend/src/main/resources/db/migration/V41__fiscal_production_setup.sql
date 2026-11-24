-- Alta fiscal para producción (4 pasos) y conciliación automática de comprobantes en revisión.

CREATE TABLE fiscal_production_setup (
    tenant_id                    uuid         PRIMARY KEY REFERENCES tenant_fiscal_issuer (tenant_id),
    -- Paso 2: usuario secundario SOL (la clave va cifrada con SecretCipher y contexto por tienda).
    sol_user                     varchar(20),
    sol_password_ciphertext      bytea,
    -- Paso 3: certificado digital .pfx (cifrado) con su clave; se guardan sujeto y vencimiento para mostrarlos.
    certificate_ciphertext       bytea,
    certificate_password_ciphertext bytea,
    certificate_subject          varchar(300),
    certificate_not_after        timestamptz,
    -- Paso 4: series autorizadas en SUNAT.
    invoice_series               varchar(4)   CHECK (invoice_series ~ '^F[A-Z0-9]{3}$'),
    receipt_series               varchar(4)   CHECK (receipt_series ~ '^B[A-Z0-9]{3}$'),
    status                       varchar(10)  NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'ACTIVE', 'DISABLED')),
    activated_at                 timestamptz,
    activated_by                 varchar(254),
    revision                     bigint       NOT NULL DEFAULT 1 CHECK (revision > 0),
    updated_at                   timestamptz  NOT NULL DEFAULT now(),
    CHECK ((sol_user IS NULL) = (sol_password_ciphertext IS NULL)),
    CHECK ((certificate_ciphertext IS NULL) = (certificate_password_ciphertext IS NULL)),
    CHECK (status <> 'ACTIVE' OR (sol_user IS NOT NULL AND certificate_ciphertext IS NOT NULL
                                  AND invoice_series IS NOT NULL AND receipt_series IS NOT NULL AND activated_at IS NOT NULL))
);

-- Comprobantes que quedaron en revisión: se consulta su estado en SUNAT con espera creciente. Nunca se reenvían solos.
ALTER TABLE pos_fiscal_request
    ADD COLUMN reconcile_attempts int NOT NULL DEFAULT 0,
    ADD COLUMN next_reconcile_at timestamptz;
CREATE INDEX ix_fiscal_reconcile_due ON pos_fiscal_request (next_reconcile_at) WHERE status = 'BETA_REVIEW';
