CREATE TABLE whatsapp_reply_receipt (
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    chat_id VARCHAR(160) NOT NULL,
    provider_id VARCHAR(200) NOT NULL,
    status VARCHAR(12) NOT NULL CHECK (status IN ('DELIVERED','READ')),
    PRIMARY KEY (tenant_id,chat_id,provider_id)
);
