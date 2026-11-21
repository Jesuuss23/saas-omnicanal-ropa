CREATE TABLE whatsapp_manual_reply (
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    request_id UUID NOT NULL,
    chat_id VARCHAR(160) NOT NULL,
    body VARCHAR(4000) NOT NULL,
    status VARCHAR(12) NOT NULL CHECK (status IN ('REVIEW','ACCEPTED','REJECTED')),
    provider_id TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, request_id)
);
CREATE INDEX ix_whatsapp_reply_chat ON whatsapp_manual_reply(tenant_id,chat_id,created_at DESC,request_id);
