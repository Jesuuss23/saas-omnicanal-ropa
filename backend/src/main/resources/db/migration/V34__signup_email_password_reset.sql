-- Prueba gratis con enlace mágico, recuperación de contraseña de propietarios y registro de correos enviados.

ALTER TABLE tenant_owner
    ADD COLUMN full_name varchar(120),
    ADD COLUMN phone varchar(20);

-- Solicitud de prueba gratis: se confirma con el enlace que llega por correo (solo se guarda el hash del token).
CREATE TABLE signup_request (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email         varchar(254) NOT NULL,
    store_name    varchar(100) NOT NULL,
    owner_name    varchar(120) NOT NULL,
    phone         varchar(20),
    plan_code     varchar(32) REFERENCES saas_plan (code),
    token_hash    char(64)     NOT NULL UNIQUE,
    expires_at    timestamptz  NOT NULL,
    confirmed_at  timestamptz,
    tenant_id     uuid REFERENCES tenant (id),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    CHECK ((confirmed_at IS NULL) = (tenant_id IS NULL))
);
CREATE INDEX ix_signup_request_email ON signup_request (lower(email), created_at);

-- Un enlace de recuperación vigente por tienda.
CREATE TABLE owner_password_reset (
    tenant_id   uuid PRIMARY KEY REFERENCES tenant (id),
    token_hash  char(64)    NOT NULL UNIQUE,
    expires_at  timestamptz NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- Bitácora de correos. body solo se guarda cuando no hay proveedor configurado (desarrollo y pruebas).
CREATE TABLE email_log (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient    varchar(254) NOT NULL,
    template     varchar(40)  NOT NULL,
    subject      varchar(200) NOT NULL,
    status       varchar(12)  NOT NULL CHECK (status IN ('SENT', 'LOGGED', 'FAILED')),
    provider_id  varchar(100),
    error        varchar(300),
    body         text,
    created_at   timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_email_log_recipient ON email_log (lower(recipient), created_at DESC);
