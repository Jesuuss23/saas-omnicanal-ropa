package com.saas.omnicanal.shared.webhook;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Bandeja idempotente de webhooks de pago (compartida por Culqi y Mercado Pago). */
@Repository
public class PaymentWebhookEventRepository {
    private final JdbcTemplate db;

    public PaymentWebhookEventRepository(JdbcTemplate db) {
        this.db = db;
    }

    /** true si es la primera vez que vemos el evento (o si falló antes y debe reintentarse). */
    public boolean register(String provider, String eventId, UUID tenant, String type, String payloadJson) {
        return db.update("""
                INSERT INTO payment_webhook_event(provider,event_id,tenant_id,event_type,payload)
                VALUES (?,?,?,?,CAST(? AS jsonb))
                ON CONFLICT (provider,event_id) DO UPDATE SET status='RECEIVED', error=NULL
                 WHERE payment_webhook_event.status='FAILED'
                """, provider, eventId, tenant, type, payloadJson) == 1;
    }

    public void finish(String provider, String eventId, String status, String error) {
        db.update("""
                UPDATE payment_webhook_event SET status=?, error=left(?,500), processed_at=now()
                 WHERE provider=? AND event_id=?
                """, status, error, provider, eventId);
    }
}
