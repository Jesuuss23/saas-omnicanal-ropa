-- Cobros de la tienda a sus clientes con la cuenta de Mercado Pago del propio dueño (OAuth).
-- El dinero va directo a la cuenta de la tienda; el SaaS solo guarda tokens cifrados.

CREATE TABLE store_payment_account (
    tenant_id UUID PRIMARY KEY REFERENCES tenant(id),
    provider VARCHAR(16) NOT NULL DEFAULT 'MERCADOPAGO' CHECK (provider IN ('MERCADOPAGO')),
    provider_user_id BIGINT NOT NULL,
    public_key VARCHAR(100),
    access_token_ciphertext BYTEA NOT NULL,
    refresh_token_ciphertext BYTEA,
    token_expires_at TIMESTAMPTZ,
    live_mode BOOLEAN NOT NULL DEFAULT false,
    status VARCHAR(12) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','ERROR')),
    last_error VARCHAR(500),
    linked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- "state" de OAuth de un solo uso (se guarda el hash), atado a la tienda que inició la vinculación.
CREATE TABLE payment_oauth_state (
    state_hash CHAR(64) PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ
);

-- Intención de pago (preferencia de Checkout Pro) por pedido web.
CREATE TABLE web_order_payment_intent (
    tenant_id UUID NOT NULL,
    order_id UUID NOT NULL,
    provider VARCHAR(16) NOT NULL CHECK (provider IN ('MERCADOPAGO')),
    preference_id VARCHAR(100) NOT NULL,
    checkout_url VARCHAR(2048) NOT NULL,
    amount NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, order_id, provider),
    FOREIGN KEY (tenant_id, order_id) REFERENCES web_checkout_order(tenant_id, id)
);

-- Pagos reportados por la pasarela; un pago por id del proveedor.
CREATE TABLE web_order_payment (
    tenant_id UUID NOT NULL,
    provider VARCHAR(16) NOT NULL CHECK (provider IN ('MERCADOPAGO')),
    provider_payment_id VARCHAR(64) NOT NULL,
    order_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','IN_PROCESS','APPROVED','REJECTED','CANCELLED','REFUNDED','CHARGED_BACK')),
    provider_status VARCHAR(40),
    amount NUMERIC(14,2) NOT NULL,
    currency CHAR(3) NOT NULL,
    -- Pago aprobado que no se pudo aplicar (monto distinto, reserva vencida...): requiere revisión humana.
    requires_review BOOLEAN NOT NULL DEFAULT false,
    review_reason VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, provider, provider_payment_id),
    FOREIGN KEY (tenant_id, order_id) REFERENCES web_checkout_order(tenant_id, id)
);
CREATE INDEX ix_web_order_payment_order ON web_order_payment (tenant_id, order_id);

-- El pedido web ahora puede quedar pagado.
ALTER TABLE web_checkout_order DROP CONSTRAINT web_checkout_order_status_check;
ALTER TABLE web_checkout_order ADD CONSTRAINT ck_web_checkout_order_status
    CHECK (status IN ('PENDING_PAYMENT','PAID','CANCELLED','EXPIRED'));
ALTER TABLE web_checkout_order ADD COLUMN paid_at TIMESTAMPTZ;
