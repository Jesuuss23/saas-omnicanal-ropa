-- Segundo factor (TOTP) para superadmin y propietarios, enrolable escaneando un QR.
-- subject_id: correo del superadmin o id de la tienda (tenant) del propietario.
CREATE TABLE mfa_enrollment (
    subject_type        varchar(20)  NOT NULL CHECK (subject_type IN ('SUPERADMIN', 'OWNER')),
    subject_id          varchar(254) NOT NULL,
    secret_enc          bytea,
    pending_secret_enc  bytea,
    pending_created_at  timestamptz,
    confirmed_at        timestamptz,
    last_step           bigint       NOT NULL DEFAULT 0,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (subject_type, subject_id),
    CHECK (secret_enc IS NULL OR confirmed_at IS NOT NULL)
);

-- Códigos de recuperación de un solo uso (se guarda solo su hash SHA-256).
CREATE TABLE mfa_recovery_code (
    subject_type varchar(20)  NOT NULL,
    subject_id   varchar(254) NOT NULL,
    code_hash    char(64)     NOT NULL,
    used_at      timestamptz,
    PRIMARY KEY (subject_type, subject_id, code_hash),
    FOREIGN KEY (subject_type, subject_id) REFERENCES mfa_enrollment (subject_type, subject_id) ON DELETE CASCADE
);

-- Límite de intentos del login de propietarios por correo (5 fallos cada 5 minutos).
CREATE TABLE owner_login_attempt (
    email          varchar(254) PRIMARY KEY,
    failures       int          NOT NULL DEFAULT 0,
    window_started timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX ix_tenant_owner_email ON tenant_owner (lower(email));
