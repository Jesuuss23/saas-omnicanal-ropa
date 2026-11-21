CREATE TABLE web_checkout_order (
    tenant_id UUID NOT NULL,
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    reservation_id UUID NOT NULL,
    guest_name VARCHAR(150) NOT NULL,
    guest_phone VARCHAR(16) NOT NULL,
    merchandise_total NUMERIC NOT NULL CHECK (merchandise_total > 0),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING_PAYMENT'
        CHECK (status IN ('PENDING_PAYMENT','CANCELLED','EXPIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id,id),
    UNIQUE (tenant_id,reservation_id),
    FOREIGN KEY (tenant_id,reservation_id) REFERENCES cart_reservation(tenant_id,id)
);
