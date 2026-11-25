-- Multimedia de WhatsApp, subcategorías de producto y catálogos PDF generados.

-- Subcategoría opcional dentro de la categoría (Polos → Retro, Verano, Invierno).
ALTER TABLE product ADD COLUMN subcategory varchar(50);

-- Archivos de WhatsApp (entrantes y enviados) guardados en la base, igual que las fotos de producto.
CREATE TABLE whatsapp_media (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid         NOT NULL REFERENCES tenant (id),
    kind        varchar(8)   NOT NULL CHECK (kind IN ('IMAGE', 'VIDEO', 'AUDIO', 'DOCUMENT')),
    mime_type   varchar(100) NOT NULL,
    file_name   varchar(200),
    content     bytea        NOT NULL CHECK (octet_length(content) BETWEEN 1 AND 16777216),
    created_at  timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_whatsapp_media_tenant ON whatsapp_media (tenant_id, created_at);

-- media_url apunta al archivo guardado (/media/{id}); queda null mientras se descarga o si no se pudo descargar.
ALTER TABLE whatsapp_inbound_message
    ADD COLUMN media_type   varchar(8)   CHECK (media_type IN ('IMAGE', 'VIDEO', 'AUDIO', 'DOCUMENT')),
    ADD COLUMN mime_type    varchar(100),
    ADD COLUMN media_url    varchar(300),
    ADD COLUMN caption      varchar(4000),
    ADD COLUMN media_status varchar(10)  CHECK (media_status IN ('PENDING', 'STORED', 'FAILED', 'TOO_LARGE'));
ALTER TABLE whatsapp_manual_reply
    ADD COLUMN media_type   varchar(8)   CHECK (media_type IN ('IMAGE', 'VIDEO', 'AUDIO', 'DOCUMENT')),
    ADD COLUMN mime_type    varchar(100),
    ADD COLUMN media_url    varchar(300),
    ADD COLUMN caption      varchar(4000);

-- El PDF generado desde el catálogo puede pesar más que uno subido a mano (fotos incluidas): hasta 10 MiB.
ALTER TABLE category_catalog_file DROP CONSTRAINT category_catalog_file_content_check;
ALTER TABLE category_catalog_file ADD CONSTRAINT category_catalog_file_content_check
    CHECK (octet_length(content) BETWEEN 1 AND 10485760);

-- Enviar imágenes y videos por WhatsApp (bandeja e IA): solo el plan más alto (y los pilotos).
INSERT INTO saas_plan_entitlement (plan_code, entitlement_key, enabled, limit_value)
SELECT code, 'whatsapp.media.send', code IN ('PREMIUM', 'PILOTO'), NULL FROM saas_plan
ON CONFLICT (plan_code, entitlement_key) DO NOTHING;
