CREATE TABLE whatsapp_inbound_message (
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    chat_id VARCHAR(160) NOT NULL,
    message_id VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, chat_id, message_id)
);
CREATE INDEX ix_whatsapp_inbound_recent ON whatsapp_inbound_message
    (tenant_id, received_at DESC, chat_id, message_id);
