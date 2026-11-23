-- Consola del superadmin: suspensión de tiendas (kill switch) y bitácora de acciones.
ALTER TABLE tenant
    ADD COLUMN suspended_at timestamptz,
    ADD COLUMN suspended_reason varchar(300),
    ADD CONSTRAINT ck_tenant_suspension CHECK ((suspended_at IS NULL) = (suspended_reason IS NULL));

CREATE TABLE platform_audit_log (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor       varchar(254) NOT NULL,
    action      varchar(60)  NOT NULL,
    tenant_id   uuid REFERENCES tenant (id),
    detail      varchar(500),
    created_at  timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_platform_audit_created ON platform_audit_log (created_at DESC);
CREATE INDEX ix_platform_audit_tenant ON platform_audit_log (tenant_id, created_at DESC);
