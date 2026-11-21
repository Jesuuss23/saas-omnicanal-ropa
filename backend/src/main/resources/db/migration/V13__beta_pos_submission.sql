ALTER TABLE beta_pos_document_number
    ADD COLUMN submitted_at TIMESTAMPTZ,
    ADD COLUMN provider_response JSONB;
