-- Equipo de la tienda: el propietario invita a encargados y vendedores con su propio acceso.
-- El propietario sigue en tenant_owner; aquí solo están los demás miembros.
CREATE TABLE store_member (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             uuid         NOT NULL REFERENCES tenant (id),
    email                 varchar(254) NOT NULL,
    full_name             varchar(120) NOT NULL,
    role                  varchar(12)  NOT NULL CHECK (role IN ('MANAGER', 'SELLER')),
    password_hash         varchar(255),
    invitation_hash       char(64),
    invitation_expires_at timestamptz,
    active                boolean      NOT NULL DEFAULT true,
    last_login_at         timestamptz,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    CHECK ((invitation_hash IS NULL) = (invitation_expires_at IS NULL))
);
CREATE UNIQUE INDEX uq_store_member_email ON store_member (tenant_id, lower(email));
CREATE INDEX ix_store_member_login ON store_member (lower(email)) WHERE password_hash IS NOT NULL;
