-- Suscripción del SaaS (cobro recurrente con Culqi) y entitlements.
-- El billing dice qué plan compró la tienda; los entitlements traducen eso en capacidades y cuotas
-- que el backend verifica. Nada de "if plan == pro" en el código.

CREATE TABLE saas_plan (
    code VARCHAR(32) PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z0-9_]{1,31}$'),
    name VARCHAR(80) NOT NULL,
    monthly_price_cents INTEGER NOT NULL CHECK (monthly_price_cents >= 0),
    currency CHAR(3) NOT NULL DEFAULT 'PEN',
    -- Id del plan creado en el panel de Culqi (pln_...). Se configura desde el superadmin.
    culqi_plan_id VARCHAR(64) UNIQUE,
    public BOOLEAN NOT NULL DEFAULT true,
    sort_order INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE saas_plan_entitlement (
    plan_code VARCHAR(32) NOT NULL REFERENCES saas_plan(code),
    entitlement_key VARCHAR(64) NOT NULL CHECK (entitlement_key ~ '^[a-z][a-z0-9_.]{1,63}$'),
    enabled BOOLEAN NOT NULL DEFAULT true,
    limit_value BIGINT CHECK (limit_value >= 0), -- NULL = sin límite
    PRIMARY KEY (plan_code, entitlement_key)
);

CREATE TABLE tenant_subscription (
    tenant_id UUID PRIMARY KEY REFERENCES tenant(id),
    plan_code VARCHAR(32) NOT NULL REFERENCES saas_plan(code),
    status VARCHAR(16) NOT NULL CHECK (status IN
        ('TRIAL','TRIAL_EXPIRED','PENDING_PAYMENT','ACTIVE','GRACE','SUSPENDED','CANCELLED')),
    trial_ends_at TIMESTAMPTZ,
    current_period_end TIMESTAMPTZ,
    grace_ends_at TIMESTAMPTZ,
    pending_plan_code VARCHAR(32) REFERENCES saas_plan(code),
    culqi_customer_id VARCHAR(64),
    culqi_card_id VARCHAR(64),
    culqi_subscription_id VARCHAR(64) UNIQUE,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (status <> 'PENDING_PAYMENT' OR pending_plan_code IS NOT NULL)
);
CREATE INDEX ix_tenant_subscription_status ON tenant_subscription (status, current_period_end);

-- Overrides por tienda: acceso temporal a una función, clientes grandfathered, etc.
CREATE TABLE tenant_entitlement_override (
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    entitlement_key VARCHAR(64) NOT NULL CHECK (entitlement_key ~ '^[a-z][a-z0-9_.]{1,63}$'),
    enabled BOOLEAN NOT NULL DEFAULT true,
    limit_value BIGINT CHECK (limit_value >= 0),
    expires_at TIMESTAMPTZ,
    reason VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, entitlement_key)
);

-- Historial de cambios de estado de la suscripción (auditoría y panel comercial).
CREATE TABLE tenant_subscription_event (
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    from_status VARCHAR(16),
    to_status VARCHAR(16) NOT NULL,
    plan_code VARCHAR(32),
    source VARCHAR(32) NOT NULL,
    detail VARCHAR(300),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id)
);

-- Bandeja de webhooks de pago (Culqi y Mercado Pago). La PK garantiza idempotencia por evento.
CREATE TABLE payment_webhook_event (
    provider VARCHAR(16) NOT NULL CHECK (provider IN ('CULQI','MERCADOPAGO')),
    event_id VARCHAR(200) NOT NULL,
    tenant_id UUID REFERENCES tenant(id),
    event_type VARCHAR(100),
    payload JSONB NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('RECEIVED','PROCESSED','IGNORED','FAILED')),
    error VARCHAR(500),
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,
    PRIMARY KEY (provider, event_id)
);
CREATE INDEX ix_payment_webhook_pending ON payment_webhook_event (status, received_at) WHERE status IN ('RECEIVED','FAILED');

-- Planes de referencia (sección 15 del documento). Precios en céntimos de sol; se calibran luego.
INSERT INTO saas_plan(code,name,monthly_price_cents,public,sort_order) VALUES
    ('TRIAL','Prueba gratuita',0,false,0),
    ('PILOTO','Tienda piloto',0,false,0),
    ('BASICO','Básico',9900,true,1),
    ('PRO','Pro',19900,true,2),
    ('PREMIUM','Premium',29900,true,3);

INSERT INTO saas_plan_entitlement(plan_code,entitlement_key,enabled,limit_value) VALUES
    ('TRIAL','catalog.products',true,20),
    ('TRIAL','team.users',true,2),
    ('TRIAL','ai.responses.monthly',true,100),
    ('TRIAL','fiscal.documents.monthly',true,50),
    ('TRIAL','fiscal.production',false,NULL),
    ('TRIAL','whatsapp.evolution',true,NULL),
    ('TRIAL','whatsapp.cloud_api',true,NULL),
    ('TRIAL','domain.custom',false,NULL),
    ('TRIAL','campaigns.email',false,NULL),
    ('TRIAL','campaigns.whatsapp',false,NULL),
    ('TRIAL','payments.mercadopago',true,NULL),

    ('BASICO','catalog.products',true,100),
    ('BASICO','team.users',true,2),
    ('BASICO','ai.responses.monthly',true,500),
    ('BASICO','fiscal.documents.monthly',true,100),
    ('BASICO','fiscal.production',true,NULL),
    ('BASICO','whatsapp.evolution',true,NULL),
    ('BASICO','whatsapp.cloud_api',false,NULL),
    ('BASICO','domain.custom',false,NULL),
    ('BASICO','campaigns.email',false,NULL),
    ('BASICO','campaigns.whatsapp',false,NULL),
    ('BASICO','payments.mercadopago',true,NULL),

    ('PRO','catalog.products',true,500),
    ('PRO','team.users',true,5),
    ('PRO','ai.responses.monthly',true,2000),
    ('PRO','fiscal.documents.monthly',true,500),
    ('PRO','fiscal.production',true,NULL),
    ('PRO','whatsapp.evolution',true,NULL),
    ('PRO','whatsapp.cloud_api',true,NULL),
    ('PRO','domain.custom',true,NULL),
    ('PRO','campaigns.email',true,NULL),
    ('PRO','campaigns.whatsapp',true,1000),
    ('PRO','payments.mercadopago',true,NULL),

    ('PREMIUM','catalog.products',true,NULL),
    ('PREMIUM','team.users',true,10),
    ('PREMIUM','ai.responses.monthly',true,5000),
    ('PREMIUM','fiscal.documents.monthly',true,NULL),
    ('PREMIUM','fiscal.production',true,NULL),
    ('PREMIUM','whatsapp.evolution',false,NULL),
    ('PREMIUM','whatsapp.cloud_api',true,NULL),
    ('PREMIUM','domain.custom',true,NULL),
    ('PREMIUM','campaigns.email',true,NULL),
    ('PREMIUM','campaigns.whatsapp',true,NULL),
    ('PREMIUM','payments.mercadopago',true,NULL);

-- PILOTO = mismas capacidades que PREMIUM, más Evolution, sin cobro (tiendas de la Fase 0).
INSERT INTO saas_plan_entitlement(plan_code,entitlement_key,enabled,limit_value)
SELECT 'PILOTO',entitlement_key,true,limit_value FROM saas_plan_entitlement WHERE plan_code='PREMIUM';

-- Las tiendas que ya existían son pilotos: no se les aplica la cuota del trial.
INSERT INTO tenant_subscription(tenant_id,plan_code,status)
SELECT id,'PILOTO','ACTIVE' FROM tenant;
