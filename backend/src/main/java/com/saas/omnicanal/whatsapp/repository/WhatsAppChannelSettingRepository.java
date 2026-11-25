package com.saas.omnicanal.whatsapp.repository;

import com.saas.omnicanal.whatsapp.model.WhatsAppProvider;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WhatsAppChannelSettingRepository {
    private final JdbcTemplate db;

    public WhatsAppChannelSettingRepository(JdbcTemplate db) {
        this.db = db;
    }

    /** Sin fila, la tienda usa Evolution (comportamiento histórico). */
    public WhatsAppProvider activeProvider(UUID tenant) {
        return db.query("SELECT active_provider FROM whatsapp_channel_setting WHERE tenant_id=?",
                        (rs, n) -> WhatsAppProvider.valueOf(rs.getString(1)), tenant)
                .stream().findFirst().orElse(WhatsAppProvider.EVOLUTION);
    }

    public void setActiveProvider(UUID tenant, WhatsAppProvider provider) {
        db.update("""
                INSERT INTO whatsapp_channel_setting(tenant_id,active_provider) VALUES (?,?)
                ON CONFLICT (tenant_id) DO UPDATE SET active_provider=excluded.active_provider, updated_at=now()
                """, tenant, provider.name());
    }

    public boolean evolutionInstanceBound(UUID tenant, String instance) {
        return Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM tenant WHERE id=? AND whatsapp_instance_id=?)", Boolean.class, tenant, instance));
    }

    /** Asigna la instancia determinística de Evolution a la tienda; falla si ya tiene otra. */
    public boolean bindEvolutionInstance(UUID tenant, String instance) {
        return db.update("""
                UPDATE tenant SET whatsapp_instance_id=? WHERE id=?
                AND (whatsapp_instance_id IS NULL OR whatsapp_instance_id=?)
                """, instance, tenant, instance) == 1;
    }

    /** Guarda el último estado de Evolution (CONNECTED, PAIRING, DISCONNECTED o UNLINKED) sin tocar el proveedor activo. */
    public void saveEvolutionState(UUID tenant, String state) {
        db.update("""
                INSERT INTO whatsapp_channel_setting(tenant_id,evolution_state,evolution_state_at) VALUES (?,?,now())
                ON CONFLICT (tenant_id) DO UPDATE SET evolution_state=excluded.evolution_state, evolution_state_at=now(), updated_at=now()
                """, tenant, state);
    }

    public record EvolutionState(String state, java.time.OffsetDateTime at) {}

    public java.util.Optional<EvolutionState> evolutionState(UUID tenant) {
        return db.query("SELECT evolution_state, evolution_state_at FROM whatsapp_channel_setting WHERE tenant_id=? AND evolution_state IS NOT NULL",
                (rs, n) -> new EvolutionState(rs.getString(1), rs.getObject(2, java.time.OffsetDateTime.class)), tenant).stream().findFirst();
    }
}
