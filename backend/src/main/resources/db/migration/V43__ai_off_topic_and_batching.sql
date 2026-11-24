-- IA vendedora: dominio estricto (preguntas fuera de tema) y agrupación de mensajes consecutivos.

-- Preguntas ajenas a la tienda seguidas: a la segunda la IA se pausa en la conversación (sin escalar a una persona).
ALTER TABLE conversation
    ADD COLUMN off_topic_count  int         NOT NULL DEFAULT 0 CHECK (off_topic_count >= 0),
    ADD COLUMN ai_paused_reason varchar(20) CHECK (ai_paused_reason IN ('OFF_TOPIC'));

-- PENDING: mensaje en espera de la ventana de agrupación. WORKING: tomado por un lote que está respondiendo.
-- BATCHED: respondido junto con otros; la respuesta queda en el mensaje indicado por batch_of.
ALTER TABLE ai_reply DROP CONSTRAINT ai_reply_status_check;
ALTER TABLE ai_reply ADD CONSTRAINT ai_reply_status_check
    CHECK (status IN ('PENDING', 'WORKING', 'SENT', 'ESCALATED', 'SKIPPED', 'FAILED', 'BATCHED'));
ALTER TABLE ai_reply ADD COLUMN batch_of varchar(200);
CREATE INDEX ix_ai_reply_pending ON ai_reply (tenant_id, chat_id, created_at) WHERE status IN ('PENDING', 'WORKING');
