-- Archivos en Cloudinary: la base guarda solo la referencia (secure_url, public_id, tipo de recurso y tamaño).
-- content (bytea) queda como respaldo para los archivos antiguos y para entornos sin Cloudinary configurado;
-- cada fila debe tener el contenido o la URL de Cloudinary.

ALTER TABLE product_image
    ADD COLUMN storage_url           varchar(500),
    ADD COLUMN storage_public_id     varchar(300),
    ADD COLUMN storage_resource_type varchar(8) CHECK (storage_resource_type IN ('image', 'video', 'raw')),
    ADD COLUMN storage_bytes         integer CHECK (storage_bytes > 0);
ALTER TABLE product_image ALTER COLUMN content DROP NOT NULL;
ALTER TABLE product_image ADD CONSTRAINT product_image_content_or_storage
    CHECK (content IS NOT NULL OR storage_url IS NOT NULL);

ALTER TABLE store_appearance
    ADD COLUMN logo_storage_url           varchar(500),
    ADD COLUMN logo_storage_public_id     varchar(300),
    ADD COLUMN logo_storage_resource_type varchar(8) CHECK (logo_storage_resource_type IN ('image', 'video', 'raw')),
    ADD COLUMN logo_storage_bytes         integer CHECK (logo_storage_bytes > 0);
-- Antes: logo y logo_media_type van juntos. Ahora: el tipo existe si y solo si hay logo (en la base o en Cloudinary).
ALTER TABLE store_appearance DROP CONSTRAINT IF EXISTS store_appearance_check;
ALTER TABLE store_appearance ADD CONSTRAINT store_appearance_logo_storage_check
    CHECK ((logo_media_type IS NULL) = (logo IS NULL AND logo_storage_url IS NULL));

ALTER TABLE category_catalog_file
    ADD COLUMN storage_url           varchar(500),
    ADD COLUMN storage_public_id     varchar(300),
    ADD COLUMN storage_resource_type varchar(8) CHECK (storage_resource_type IN ('image', 'video', 'raw')),
    ADD COLUMN storage_bytes         integer CHECK (storage_bytes > 0);
ALTER TABLE category_catalog_file ALTER COLUMN content DROP NOT NULL;
ALTER TABLE category_catalog_file ADD CONSTRAINT category_catalog_file_content_or_storage
    CHECK (content IS NOT NULL OR storage_url IS NOT NULL);

ALTER TABLE whatsapp_media
    ADD COLUMN storage_url           varchar(500),
    ADD COLUMN storage_public_id     varchar(300),
    ADD COLUMN storage_resource_type varchar(8) CHECK (storage_resource_type IN ('image', 'video', 'raw')),
    ADD COLUMN storage_bytes         integer CHECK (storage_bytes > 0);
ALTER TABLE whatsapp_media ALTER COLUMN content DROP NOT NULL;
ALTER TABLE whatsapp_media ADD CONSTRAINT whatsapp_media_content_or_storage
    CHECK (content IS NOT NULL OR storage_url IS NOT NULL);

-- media_url de los mensajes puede ser una secure_url completa de Cloudinary.
ALTER TABLE whatsapp_inbound_message ALTER COLUMN media_url TYPE varchar(500);
ALTER TABLE whatsapp_manual_reply ALTER COLUMN media_url TYPE varchar(500);

-- La migración de archivos antiguos busca filas con contenido y sin URL.
CREATE INDEX ix_product_image_pending_storage ON product_image (id) WHERE storage_url IS NULL;
CREATE INDEX ix_whatsapp_media_pending_storage ON whatsapp_media (id) WHERE storage_url IS NULL;
