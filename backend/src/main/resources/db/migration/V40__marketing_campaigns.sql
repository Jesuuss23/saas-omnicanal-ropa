-- Marketing: consentimiento por contacto, campañas con cola de envío y recordatorio de carrito abandonado.

-- Consentimiento (Ley 29733): sin aceptación expresa el contacto no entra en campañas.
ALTER TABLE contact
    ADD COLUMN email_opt_in boolean NOT NULL DEFAULT false,
    ADD COLUMN whatsapp_opt_in boolean NOT NULL DEFAULT false,
    ADD COLUMN consent_at timestamptz,
    ADD COLUMN unsubscribed_at timestamptz,
    ADD COLUMN unsubscribe_token char(32) NOT NULL DEFAULT replace(gen_random_uuid()::text, '-', '');
CREATE UNIQUE INDEX uq_contact_unsubscribe ON contact (unsubscribe_token);

-- El comprador acepta (o no) recibir novedades al dejar sus datos en el checkout.
ALTER TABLE cart_reservation ADD COLUMN marketing_opt_in boolean NOT NULL DEFAULT false;

CREATE TABLE campaign (
    tenant_id     uuid          NOT NULL REFERENCES tenant (id),
    id            uuid          NOT NULL DEFAULT gen_random_uuid(),
    name          varchar(120)  NOT NULL,
    channel       varchar(8)    NOT NULL CHECK (channel IN ('EMAIL', 'WHATSAPP')),
    segment       jsonb         NOT NULL DEFAULT '{}',
    subject       varchar(150),
    body          varchar(5000),
    template_name varchar(512),
    template_lang varchar(15),
    status        varchar(10)   NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'SENDING', 'SENT', 'CANCELLED')),
    created_by    varchar(254)  NOT NULL,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    started_at    timestamptz,
    finished_at   timestamptz,
    revision      bigint        NOT NULL DEFAULT 1 CHECK (revision > 0),
    PRIMARY KEY (tenant_id, id),
    CHECK (channel <> 'EMAIL' OR (subject IS NOT NULL AND body IS NOT NULL)),
    CHECK (channel <> 'WHATSAPP' OR (template_name IS NOT NULL AND template_lang IS NOT NULL))
);
CREATE INDEX ix_campaign_recent ON campaign (tenant_id, created_at DESC);

-- Cola de envío: un trabajador toma lotes con FOR UPDATE SKIP LOCKED, así nunca se envía dos veces a la misma persona.
CREATE TABLE campaign_recipient (
    tenant_id    uuid         NOT NULL,
    campaign_id  uuid         NOT NULL,
    contact_id   uuid         NOT NULL,
    destination  varchar(254) NOT NULL,
    status       varchar(8)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED')),
    attempts     int          NOT NULL DEFAULT 0,
    error        varchar(300),
    sent_at      timestamptz,
    PRIMARY KEY (tenant_id, campaign_id, contact_id),
    FOREIGN KEY (tenant_id, campaign_id) REFERENCES campaign (tenant_id, id),
    FOREIGN KEY (tenant_id, contact_id) REFERENCES contact (tenant_id, id)
);
CREATE INDEX ix_campaign_recipient_queue ON campaign_recipient (status, tenant_id, campaign_id) WHERE status = 'PENDING';

-- Recordatorio de carrito abandonado por WhatsApp (plantilla aprobada de Cloud API), una vez por reserva.
CREATE TABLE abandoned_cart_settings (
    tenant_id      uuid         PRIMARY KEY REFERENCES tenant (id),
    enabled        boolean      NOT NULL DEFAULT false,
    delay_minutes  int          NOT NULL DEFAULT 60 CHECK (delay_minutes BETWEEN 15 AND 1440),
    template_name  varchar(512),
    template_lang  varchar(15),
    revision       bigint       NOT NULL DEFAULT 1 CHECK (revision > 0),
    CHECK (NOT enabled OR (template_name IS NOT NULL AND template_lang IS NOT NULL))
);

CREATE TABLE abandoned_cart_reminder (
    tenant_id       uuid         NOT NULL,
    reservation_id  uuid         NOT NULL,
    phone           varchar(15)  NOT NULL,
    status          varchar(8)   NOT NULL CHECK (status IN ('SENT', 'FAILED', 'SKIPPED')),
    error           varchar(300),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, reservation_id),
    FOREIGN KEY (tenant_id, reservation_id) REFERENCES cart_reservation (tenant_id, id)
);
