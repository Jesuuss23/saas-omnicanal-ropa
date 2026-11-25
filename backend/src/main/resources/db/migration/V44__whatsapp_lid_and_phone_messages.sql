-- Evolution API 2.3.x (Baileys 7): chats con identificador @lid y mensajes enviados desde el celular de la tienda.

-- Teléfono visible de la conversación. En chats @lid el identificador no es el número: llega en key.remoteJidAlt.
ALTER TABLE conversation ADD COLUMN phone varchar(15) CHECK (phone ~ '^[0-9]{5,15}$');

-- PHONE: mensaje escrito desde el celular conectado (fromMe en el webhook), no desde el panel.
ALTER TABLE whatsapp_manual_reply DROP CONSTRAINT whatsapp_manual_reply_author_check;
ALTER TABLE whatsapp_manual_reply ADD CONSTRAINT whatsapp_manual_reply_author_check CHECK (author IN ('HUMAN', 'AI', 'PHONE'));
CREATE INDEX ix_whatsapp_reply_provider ON whatsapp_manual_reply (tenant_id, chat_id, provider_id) WHERE provider_id IS NOT NULL;
