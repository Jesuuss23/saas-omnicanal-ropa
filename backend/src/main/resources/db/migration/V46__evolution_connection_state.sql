-- Último estado conocido de la instancia de Evolution (consulta en vivo o webhook CONNECTION_UPDATE).
ALTER TABLE whatsapp_channel_setting
    ADD COLUMN evolution_state VARCHAR(16) CHECK (evolution_state IN ('CONNECTED','PAIRING','DISCONNECTED','UNLINKED')),
    ADD COLUMN evolution_state_at TIMESTAMPTZ;
