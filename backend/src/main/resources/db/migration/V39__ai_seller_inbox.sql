-- IA vendedora e inbox con escalamiento.

-- Interruptor global de la plataforma (el superadmin apaga la IA de todas las tiendas a la vez).
CREATE TABLE platform_flag (
    key         varchar(60)  PRIMARY KEY CHECK (key ~ '^[a-z][a-z0-9_.]{1,59}$'),
    enabled     boolean      NOT NULL,
    updated_by  varchar(254) NOT NULL,
    updated_at  timestamptz  NOT NULL DEFAULT now()
);
INSERT INTO platform_flag (key, enabled, updated_by) VALUES ('ai.replies', true, 'migración');

-- Configuración de la IA por tienda. Arranca apagada: el dueño la activa cuando revisó su catálogo y el tono.
CREATE TABLE store_ai_settings (
    tenant_id     uuid          PRIMARY KEY REFERENCES tenant (id),
    enabled       boolean       NOT NULL DEFAULT false,
    instructions  varchar(2000) NOT NULL DEFAULT '',
    sla_minutes   int           NOT NULL DEFAULT 15 CHECK (sla_minutes BETWEEN 1 AND 1440),
    revision      bigint        NOT NULL DEFAULT 1 CHECK (revision > 0),
    updated_at    timestamptz   NOT NULL DEFAULT now()
);

-- Estado de cada conversación de WhatsApp (una por chat y tienda).
CREATE TABLE conversation (
    tenant_id          uuid         NOT NULL REFERENCES tenant (id),
    chat_id            varchar(160) NOT NULL,
    status             varchar(12)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ESCALATED', 'CLOSED')),
    ai_enabled         boolean      NOT NULL DEFAULT true,
    assignee           varchar(254),
    escalated_at       timestamptz,
    escalation_reason  varchar(300),
    summary            varchar(2000),
    handback_note      varchar(1000),
    last_inbound_at    timestamptz,
    last_outbound_at   timestamptz,
    alerted_at         timestamptz,
    revision           bigint       NOT NULL DEFAULT 1 CHECK (revision > 0),
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, chat_id),
    CHECK ((status = 'ESCALATED') = (escalated_at IS NOT NULL))
);
CREATE INDEX ix_conversation_inbox ON conversation (tenant_id, status, last_inbound_at DESC);

-- Conversaciones que ya existían en el historial.
INSERT INTO conversation (tenant_id, chat_id, last_inbound_at)
SELECT tenant_id, chat_id, max(received_at) FROM whatsapp_inbound_message GROUP BY tenant_id, chat_id;

-- Quién escribió cada respuesta saliente: una persona del equipo o la IA.
ALTER TABLE whatsapp_manual_reply ADD COLUMN author varchar(5) NOT NULL DEFAULT 'HUMAN' CHECK (author IN ('HUMAN', 'AI'));

-- Cada mensaje entrante se procesa una sola vez por la IA (aunque el proveedor reenvíe el webhook).
CREATE TABLE ai_reply (
    tenant_id   uuid         NOT NULL REFERENCES tenant (id),
    chat_id     varchar(160) NOT NULL,
    message_id  varchar(200) NOT NULL,
    status      varchar(10)  NOT NULL CHECK (status IN ('PENDING', 'SENT', 'ESCALATED', 'SKIPPED', 'FAILED')),
    skip_reason varchar(40),
    reply       varchar(4000),
    tools_used  varchar(300),
    latency_ms  int,
    error       varchar(500),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, chat_id, message_id)
);
-- Para la cuota mensual del plan (ai.responses.monthly): respuestas enviadas en el mes.
CREATE INDEX ix_ai_reply_month ON ai_reply (tenant_id, created_at) WHERE status = 'SENT';
