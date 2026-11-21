CREATE TABLE crm_follow_up_history (
    tenant_id UUID NOT NULL,
    chat_id VARCHAR(160) NOT NULL,
    revision BIGINT NOT NULL,
    scheduled_date DATE NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('CONTACTED','NO_RESPONSE','DECLINED')),
    note VARCHAR(500) NOT NULL,
    PRIMARY KEY (tenant_id,chat_id,revision),
    FOREIGN KEY (tenant_id,chat_id) REFERENCES crm_contact_stage(tenant_id,chat_id)
);
