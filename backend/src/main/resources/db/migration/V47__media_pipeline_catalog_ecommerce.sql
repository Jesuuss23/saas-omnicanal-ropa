-- Parte A: análisis de archivos entrantes con caché. Cada resultado se guarda una sola vez y nunca se vuelve a pedir.
ALTER TABLE whatsapp_media
    ADD COLUMN content_sha256     char(64),
    ADD COLUMN transcript         text,
    ADD COLUMN transcript_status  varchar(10) CHECK (transcript_status IN ('DONE', 'FAILED', 'DISABLED', 'EMPTY')),
    ADD COLUMN moderation_status  varchar(14) CHECK (moderation_status IN ('SAFE', 'BLOCKED_NSFW', 'FAILED', 'DISABLED')),
    ADD COLUMN moderation_score   numeric(5, 4) CHECK (moderation_score BETWEEN 0 AND 1),
    ADD COLUMN vision_status      varchar(10) CHECK (vision_status IN ('DONE', 'FAILED', 'DISABLED', 'SKIPPED')),
    ADD COLUMN vision_analysis_json jsonb,
    ADD COLUMN processed_at       timestamptz;
-- El mismo archivo reenviado reutiliza los resultados (caché por contenido dentro de la tienda).
CREATE INDEX ix_whatsapp_media_sha ON whatsapp_media (tenant_id, content_sha256) WHERE content_sha256 IS NOT NULL;

ALTER TABLE whatsapp_inbound_message DROP CONSTRAINT IF EXISTS whatsapp_inbound_message_media_status_check;
ALTER TABLE whatsapp_inbound_message ALTER COLUMN media_status TYPE varchar(14);
ALTER TABLE whatsapp_inbound_message
    ADD CONSTRAINT whatsapp_inbound_message_media_status_check
        CHECK (media_status IN ('PENDING', 'STORED', 'FAILED', 'TOO_LARGE', 'BLOCKED_NSFW')),
    -- Texto que ve la IA en lugar del cuerpo: la transcripción o el contexto del análisis visual.
    ADD COLUMN ai_context varchar(4000);

ALTER TABLE conversation DROP CONSTRAINT IF EXISTS conversation_ai_paused_reason_check;
ALTER TABLE conversation ADD CONSTRAINT conversation_ai_paused_reason_check CHECK (ai_paused_reason IN ('OFF_TOPIC', 'NSFW'));

-- Incidencias de archivos (contenido explícito o imagen ajena a la tienda) para revisión del equipo.
CREATE TABLE whatsapp_media_incident (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   uuid         NOT NULL REFERENCES tenant(id),
    chat_id     varchar(160) NOT NULL,
    message_id  varchar(200) NOT NULL,
    media_id    uuid,
    kind        varchar(12)  NOT NULL CHECK (kind IN ('NSFW', 'OFF_CONTEXT')),
    score       numeric(5, 4),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, chat_id, message_id, kind)
);
CREATE INDEX ix_whatsapp_media_incident ON whatsapp_media_incident (tenant_id, created_at DESC);

-- Qué hace la tienda ante contenido explícito: siempre avisa; por defecto además pausa la IA en esa conversación.
ALTER TABLE store_ai_settings ADD COLUMN nsfw_pause_ai boolean NOT NULL DEFAULT true;
ALTER TABLE store_ai_settings ALTER COLUMN instructions TYPE varchar(4000);

-- Parte B: catálogo con estándar e-commerce.
ALTER TABLE product
    ADD COLUMN active   boolean     NOT NULL DEFAULT true,
    ADD COLUMN material varchar(80);
ALTER TABLE product_variant
    -- Precio de lista antes del descuento (precio tachado). Debe ser mayor que el precio de venta.
    ADD COLUMN compare_at_price numeric(10, 2) CHECK (compare_at_price IS NULL OR compare_at_price > 0),
    -- Costo unitario, solo para el margen del dueño; nunca se muestra en la tienda.
    ADD COLUMN cost numeric(10, 2) CHECK (cost IS NULL OR cost >= 0);
