-- Identidad unificada del contacto: una persona por tienda, reconocida por teléfono, correo o cuenta de Google.
-- Teléfono en E.164 sin "+" (igual que el chat de WhatsApp: 51987654321@s.whatsapp.net).
CREATE TABLE contact (
    tenant_id    uuid         NOT NULL REFERENCES tenant (id),
    id           uuid         NOT NULL DEFAULT gen_random_uuid(),
    full_name    varchar(150),
    phone        varchar(15)  CHECK (phone ~ '^[0-9]{7,15}$'),
    email        varchar(254) CHECK (email = lower(email) AND position('@' IN email) > 1),
    google_sub   varchar(64),
    merged_into  uuid,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, merged_into) REFERENCES contact (tenant_id, id),
    CHECK (merged_into IS NULL OR merged_into <> id),
    CHECK (num_nonnulls(phone, email, google_sub) > 0)
);
-- Solo los contactos vigentes (no fusionados) reclaman su teléfono, correo o cuenta de Google.
CREATE UNIQUE INDEX uq_contact_phone ON contact (tenant_id, phone) WHERE merged_into IS NULL AND phone IS NOT NULL;
CREATE UNIQUE INDEX uq_contact_email ON contact (tenant_id, email) WHERE merged_into IS NULL AND email IS NOT NULL;
CREATE UNIQUE INDEX uq_contact_google ON contact (tenant_id, google_sub) WHERE merged_into IS NULL AND google_sub IS NOT NULL;
CREATE INDEX ix_contact_recent ON contact (tenant_id, updated_at DESC) WHERE merged_into IS NULL;

-- Cada fusión guarda cómo estaban ambos contactos y qué pedidos se movieron, para poder deshacerla.
CREATE TABLE contact_merge_log (
    tenant_id    uuid         NOT NULL,
    id           uuid         NOT NULL DEFAULT gen_random_uuid(),
    survivor_id  uuid         NOT NULL,
    merged_id    uuid         NOT NULL,
    survivor_before jsonb     NOT NULL,
    moved_orders uuid[]       NOT NULL DEFAULT '{}',
    reason       varchar(16)  NOT NULL CHECK (reason IN ('MANUAL')),
    merged_by    varchar(254) NOT NULL,
    merged_at    timestamptz  NOT NULL DEFAULT now(),
    reverted_at  timestamptz,
    reverted_by  varchar(254),
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id, survivor_id) REFERENCES contact (tenant_id, id),
    FOREIGN KEY (tenant_id, merged_id) REFERENCES contact (tenant_id, id),
    CHECK ((reverted_at IS NULL) = (reverted_by IS NULL))
);
CREATE INDEX ix_contact_merge_recent ON contact_merge_log (tenant_id, merged_at DESC);

ALTER TABLE web_checkout_order ADD COLUMN contact_id uuid,
    ADD CONSTRAINT fk_web_order_contact FOREIGN KEY (tenant_id, contact_id) REFERENCES contact (tenant_id, id);
CREATE INDEX ix_web_order_contact ON web_checkout_order (tenant_id, contact_id, created_at DESC) WHERE contact_id IS NOT NULL;

-- Sesión del comprador en la tienda pública. En la base va solo el SHA-256 del token de la cookie.
CREATE TABLE customer_session (
    token_hash   char(64)     PRIMARY KEY,
    tenant_id    uuid         NOT NULL,
    contact_id   uuid         NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    expires_at   timestamptz  NOT NULL,
    FOREIGN KEY (tenant_id, contact_id) REFERENCES contact (tenant_id, id)
);
CREATE INDEX ix_customer_session_expiry ON customer_session (expires_at);

-- Contactos a partir de los pedidos web existentes: un contacto por teléfono y tienda, con el nombre más reciente.
-- Los celulares peruanos de 9 dígitos se completan con 51.
CREATE FUNCTION normalize_phone(raw text) RETURNS text LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE WHEN d ~ '^9[0-9]{8}$' THEN '51' || d ELSE d END
      FROM (SELECT regexp_replace(coalesce(raw, ''), '[^0-9]', '', 'g') AS d) s
$$;

INSERT INTO contact (tenant_id, full_name, phone, created_at, updated_at)
SELECT DISTINCT ON (tenant_id, normalize_phone(guest_phone)) tenant_id, guest_name, normalize_phone(guest_phone),
       min(created_at) OVER w, max(created_at) OVER w
  FROM web_checkout_order
 WHERE normalize_phone(guest_phone) ~ '^[0-9]{7,15}$'
WINDOW w AS (PARTITION BY tenant_id, normalize_phone(guest_phone))
 ORDER BY tenant_id, normalize_phone(guest_phone), created_at DESC;

UPDATE web_checkout_order o SET contact_id = c.id
  FROM contact c
 WHERE c.tenant_id = o.tenant_id AND c.phone = normalize_phone(o.guest_phone) AND c.merged_into IS NULL;
