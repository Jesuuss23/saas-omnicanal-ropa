CREATE TABLE crm_contact_stage (
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    chat_id VARCHAR(160) NOT NULL,
    stage VARCHAR(16) NOT NULL CHECK (stage IN ('NEW','IN_PROGRESS','WON','LOST')),
    revision BIGINT NOT NULL CHECK (revision > 0),
    PRIMARY KEY (tenant_id,chat_id)
);

CREATE INDEX ix_whatsapp_contact_recent ON whatsapp_inbound_message
    (tenant_id,chat_id,received_at DESC,message_id DESC);
