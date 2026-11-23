-- Panel de tienda: apariencia de la tienda pública y fotos de producto.
-- Las imágenes se guardan en PostgreSQL durante el piloto (máx. 2 MB c/u, 8 por producto); luego pasan a R2/S3.

CREATE TABLE store_appearance (
    tenant_id       uuid PRIMARY KEY REFERENCES tenant (id),
    tagline         varchar(140),
    description     varchar(1000),
    primary_color   char(7)      NOT NULL DEFAULT '#0f766e' CHECK (primary_color ~ '^#[0-9a-f]{6}$'),
    whatsapp_phone  varchar(15)  CHECK (whatsapp_phone ~ '^[0-9]{8,15}$'),
    instagram       varchar(30)  CHECK (instagram ~ '^[A-Za-z0-9._]{1,30}$'),
    logo_media_type varchar(16)  CHECK (logo_media_type IN ('image/png', 'image/jpeg', 'image/webp')),
    logo            bytea        CHECK (octet_length(logo) BETWEEN 1 AND 524288),
    logo_version    integer      NOT NULL DEFAULT 0,
    revision        bigint       NOT NULL DEFAULT 1 CHECK (revision > 0),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CHECK ((logo IS NULL) = (logo_media_type IS NULL))
);

CREATE TABLE product_image (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid         NOT NULL,
    product_id  uuid         NOT NULL,
    position    smallint     NOT NULL CHECK (position BETWEEN 0 AND 7),
    media_type  varchar(16)  NOT NULL CHECK (media_type IN ('image/png', 'image/jpeg', 'image/webp')),
    content     bytea        NOT NULL CHECK (octet_length(content) BETWEEN 1 AND 2097152),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, product_id) REFERENCES product (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT uq_product_image_position UNIQUE (tenant_id, product_id, position) DEFERRABLE INITIALLY DEFERRED
);
