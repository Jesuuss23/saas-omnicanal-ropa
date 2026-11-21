ALTER TABLE beta_company_registration
    ADD COLUMN emission_token_ciphertext BYTEA,
    ADD CONSTRAINT ck_beta_token_company CHECK (
        emission_token_ciphertext IS NULL OR company_id IS NOT NULL
    );
