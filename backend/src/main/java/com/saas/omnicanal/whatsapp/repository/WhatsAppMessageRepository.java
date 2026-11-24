package com.saas.omnicanal.whatsapp.repository;

import com.saas.omnicanal.whatsapp.model.InboundMessage;
import com.saas.omnicanal.whatsapp.model.ManualReply;
import com.saas.omnicanal.whatsapp.model.WhatsAppProvider;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Mensajes entrantes, respuestas manuales y acuses de ambos proveedores (historial unificado por tenant). */
@Repository
public class WhatsAppMessageRepository {
    private final JdbcTemplate db;

    public WhatsAppMessageRepository(JdbcTemplate db) {
        this.db = db;
    }

    /** Idempotente: el mismo message_id reenviado por el proveedor no se duplica. */
    public int saveInbound(UUID tenant, String chat, String messageId, String body, WhatsAppProvider provider) {
        return db.update("""
                INSERT INTO whatsapp_inbound_message(tenant_id,chat_id,message_id,body,provider) VALUES (?,?,?,?,?)
                ON CONFLICT (tenant_id,chat_id,message_id) DO NOTHING
                """, tenant, chat, messageId, body, provider.name());
    }

    /** El acuse puede llegar antes de que el envío devuelva el id del proveedor al CRM. */
    public int saveReceipt(UUID tenant, String chat, String providerId, String status) {
        return db.update("""
                INSERT INTO whatsapp_reply_receipt(tenant_id,chat_id,provider_id,status)
                SELECT ?,?,?,? WHERE EXISTS (
                    SELECT 1 FROM whatsapp_manual_reply WHERE tenant_id=? AND chat_id=?
                      AND (provider_id=? OR (provider_id IS NULL AND status='REVIEW'
                           AND created_at>=now()-interval '5 minutes')))
                ON CONFLICT (tenant_id,chat_id,provider_id) DO UPDATE SET status=excluded.status
                 WHERE whatsapp_reply_receipt.status='DELIVERED' AND excluded.status='READ'
                """, tenant, chat, providerId, status, tenant, chat, providerId);
    }

    public List<InboundMessage> recentInbound(UUID tenant, int limit) {
        return db.query("""
                SELECT chat_id,message_id,body,received_at FROM whatsapp_inbound_message
                 WHERE tenant_id=? ORDER BY received_at DESC,chat_id,message_id LIMIT ?
                """, (rs, n) -> new InboundMessage(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getObject(4, OffsetDateTime.class)), tenant, limit);
    }

    public List<ManualReply> recentReplies(UUID tenant, String chat, int limit) {
        return db.query("""
                SELECT m.body,coalesce(r.status,m.status),m.created_at
                  FROM whatsapp_manual_reply m LEFT JOIN whatsapp_reply_receipt r
                    ON r.tenant_id=m.tenant_id AND r.chat_id=m.chat_id AND r.provider_id=m.provider_id
                 WHERE m.tenant_id=? AND m.chat_id=?
                 ORDER BY m.created_at DESC,m.request_id DESC LIMIT ?
                """, (rs, n) -> new ManualReply(rs.getString(1), rs.getString(2), rs.getObject(3, OffsetDateTime.class)),
                tenant, chat, limit);
    }

    /** Reserva la respuesta (idempotencia por request_id) solo si el chat existe en el historial de la tienda. */
    public boolean claimReply(UUID tenant, UUID requestId, String chat, String text, WhatsAppProvider provider, String author) {
        return db.update("""
                INSERT INTO whatsapp_manual_reply(tenant_id,request_id,chat_id,body,status,provider,author)
                SELECT ?,?,?,?,'REVIEW',?,? WHERE EXISTS (
                    SELECT 1 FROM whatsapp_inbound_message m WHERE m.tenant_id=? AND m.chat_id=?)
                ON CONFLICT (tenant_id,request_id) DO NOTHING
                """, tenant, requestId, chat, text, provider.name(), author, tenant, chat) == 1;
    }

    public Optional<String> existingReplyStatus(UUID tenant, UUID requestId, String chat, String text) {
        return db.query("""
                SELECT status FROM whatsapp_manual_reply WHERE tenant_id=? AND request_id=? AND chat_id=? AND body=?
                """, (rs, n) -> rs.getString(1), tenant, requestId, chat, text).stream().findFirst();
    }

    public void finishReply(UUID tenant, UUID requestId, String status, String providerId) {
        db.update("UPDATE whatsapp_manual_reply SET status=?,provider_id=? WHERE tenant_id=? AND request_id=?",
                status, providerId, tenant, requestId);
    }

    public record TestMessage(UUID id, String recipient, String kind, String templateName, String status,
                              String providerMessageId, String error, OffsetDateTime createdAt) {}

    public void saveTest(UUID tenant, String recipient, String kind, String template, String status,
                         String providerId, String error) {
        db.update("""
                INSERT INTO whatsapp_cloud_test_message(tenant_id,recipient,kind,template_name,status,provider_message_id,error)
                VALUES (?,?,?,?,?,?,left(?,500))
                """, tenant, recipient, kind, template, status, providerId, error);
    }

    public List<TestMessage> recentTests(UUID tenant) {
        return db.query("""
                SELECT id,recipient,kind,template_name,status,provider_message_id,error,created_at
                  FROM whatsapp_cloud_test_message WHERE tenant_id=? ORDER BY created_at DESC LIMIT 10
                """, (rs, n) -> new TestMessage(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getObject(8, OffsetDateTime.class)), tenant);
    }
}
