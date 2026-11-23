package com.saas.omnicanal.contact.repository;

import com.saas.omnicanal.contact.model.Contact;
import com.saas.omnicanal.contact.model.ContactViews.Merge;
import com.saas.omnicanal.contact.model.ContactViews.Row;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ContactRepository {
    private static final RowMapper<Contact> CONTACT = (rs, n) -> new Contact(rs.getObject("id", UUID.class),
            rs.getString("full_name"), rs.getString("phone"), rs.getString("email"), rs.getString("google_sub") != null,
            rs.getObject("merged_into", UUID.class), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));

    /** Columnas de la lista: pedidos, gasto y si hay chat de WhatsApp con ese número. */
    private static final String ROW_SELECT = """
            SELECT c.id, c.full_name, c.phone, c.email, c.google_sub IS NOT NULL AS google, c.updated_at,
                   EXISTS (SELECT 1 FROM whatsapp_inbound_message m WHERE m.tenant_id = c.tenant_id
                            AND m.chat_id = c.phone || '@s.whatsapp.net') AS whatsapp,
                   (SELECT count(*) FROM web_checkout_order o WHERE o.tenant_id = c.tenant_id AND o.contact_id = c.id) AS orders,
                   (SELECT coalesce(sum(o.merchandise_total + coalesce(o.delivery_fee, 0)), 0) FROM web_checkout_order o
                     WHERE o.tenant_id = c.tenant_id AND o.contact_id = c.id AND o.status = 'PAID') AS spent,
                   (SELECT max(o.created_at) FROM web_checkout_order o WHERE o.tenant_id = c.tenant_id AND o.contact_id = c.id) AS last_order
              FROM contact c
            """;
    private static final RowMapper<Row> ROW = (rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("full_name"),
            rs.getString("phone"), rs.getString("email"), rs.getBoolean("google"), rs.getBoolean("whatsapp"), rs.getLong("orders"),
            rs.getBigDecimal("spent"), instant(rs.getTimestamp("last_order")), instant(rs.getTimestamp("updated_at")));

    private final JdbcTemplate db;

    public ContactRepository(JdbcTemplate db) {
        this.db = db;
    }

    public boolean storeExists(UUID tenant) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM tenant WHERE id=?)", Boolean.class, tenant));
    }

    public Optional<Contact> find(UUID tenant, UUID id) {
        return db.query("SELECT * FROM contact WHERE tenant_id=? AND id=?", CONTACT, tenant, id).stream().findFirst();
    }

    /** Bloquea el contacto vigente con ese teléfono (o nada); evita que dos pedidos simultáneos creen duplicados. */
    public Optional<Contact> activeByPhone(UUID tenant, String phone) {
        return db.query("SELECT * FROM contact WHERE tenant_id=? AND phone=? AND merged_into IS NULL FOR UPDATE",
                CONTACT, tenant, phone).stream().findFirst();
    }

    public Optional<Contact> activeByEmail(UUID tenant, String email) {
        return db.query("SELECT * FROM contact WHERE tenant_id=? AND email=? AND merged_into IS NULL FOR UPDATE",
                CONTACT, tenant, email).stream().findFirst();
    }

    public Optional<Contact> activeByGoogle(UUID tenant, String sub) {
        return db.query("SELECT * FROM contact WHERE tenant_id=? AND google_sub=? AND merged_into IS NULL FOR UPDATE",
                CONTACT, tenant, sub).stream().findFirst();
    }

    /** Vacío si otro pedido simultáneo creó el mismo contacto (choca con un índice único): se vuelve a buscar. */
    public Optional<Contact> insert(UUID tenant, String name, String phone, String email, String googleSub) {
        return db.query("""
                INSERT INTO contact(tenant_id, full_name, phone, email, google_sub) VALUES (?,?,?,?,?)
                ON CONFLICT DO NOTHING RETURNING *
                """, CONTACT, tenant, name, phone, email, googleSub).stream().findFirst();
    }

    /** Completa solo los datos que faltan; nunca pisa un teléfono, correo o cuenta ya registrados. */
    public void fillMissing(UUID tenant, UUID id, String name, String phone, String email, String googleSub) {
        db.update("""
                UPDATE contact SET full_name = coalesce(full_name, ?), phone = coalesce(phone, ?),
                       email = coalesce(email, ?), google_sub = coalesce(google_sub, ?), updated_at = now()
                 WHERE tenant_id = ? AND id = ?
                """, name, phone, email, googleSub, tenant, id);
    }

    public void touch(UUID tenant, UUID id, String latestName) {
        db.update("UPDATE contact SET full_name = coalesce(?, full_name), updated_at = now() WHERE tenant_id=? AND id=?",
                latestName, tenant, id);
    }

    /** Nombre y teléfono que el comprador escribió en el pedido. */
    public Optional<String[]> orderGuest(UUID tenant, UUID order) {
        return db.query("SELECT guest_name, guest_phone FROM web_checkout_order WHERE tenant_id=? AND id=?",
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2)}, tenant, order).stream().findFirst();
    }

    public void linkOrder(UUID tenant, UUID order, UUID contact) {
        db.update("UPDATE web_checkout_order SET contact_id=? WHERE tenant_id=? AND id=?", contact, tenant, order);
    }

    public List<Row> list(UUID tenant, String query, int limit, long offset) {
        return db.query(ROW_SELECT + """
                 WHERE c.tenant_id = ? AND c.merged_into IS NULL
                   AND (? = '' OR strpos(lower(concat_ws(' ', c.full_name, c.phone, c.email)), lower(?)) > 0)
                 ORDER BY c.updated_at DESC, c.id LIMIT ? OFFSET ?
                """, ROW, tenant, query, query, limit, offset);
    }

    public Optional<Row> row(UUID tenant, UUID id) {
        return db.query(ROW_SELECT + " WHERE c.tenant_id = ? AND c.id = ?", ROW, tenant, id).stream().findFirst();
    }

    public record OrderRow(UUID id, UUID reservation, Instant createdAt, String status, java.math.BigDecimal total, String method) {}

    public List<OrderRow> orders(UUID tenant, UUID contact, int limit) {
        return db.query("""
                SELECT id, reservation_id, created_at, status, merchandise_total + coalesce(delivery_fee, 0), delivery_method
                  FROM web_checkout_order WHERE tenant_id=? AND contact_id=? ORDER BY created_at DESC LIMIT ?
                """, (rs, n) -> new OrderRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), instant(rs.getTimestamp(3)),
                rs.getString(4), rs.getBigDecimal(5), rs.getString(6)), tenant, contact, limit);
    }

    /**
     * Posibles duplicados: pedidos de un contacto cuyo teléfono pertenece a otro contacto vigente
     * (pasa cuando un comprador con cuenta de Google compra con el número que ya usó como invitado).
     */
    public List<UUID[]> duplicatePairs(UUID tenant, int limit) {
        return db.query("""
                SELECT DISTINCT least(o.contact_id, c.id), greatest(o.contact_id, c.id)
                  FROM web_checkout_order o
                  JOIN contact owner ON owner.tenant_id = o.tenant_id AND owner.id = o.contact_id AND owner.merged_into IS NULL
                  JOIN contact c ON c.tenant_id = o.tenant_id AND c.merged_into IS NULL AND c.id <> o.contact_id
                                AND c.phone = normalize_phone(o.guest_phone)
                 WHERE o.tenant_id = ?
                 LIMIT ?
                """, (rs, n) -> new UUID[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)}, tenant, limit);
    }

    // --- Fusión reversible ---

    public String snapshot(UUID tenant, UUID id) {
        return db.queryForObject("""
                SELECT jsonb_build_object('full_name', full_name, 'phone', phone, 'email', email, 'google_sub', google_sub)::text
                  FROM contact WHERE tenant_id=? AND id=?
                """, String.class, tenant, id);
    }

    public List<UUID> orderIds(UUID tenant, UUID contact) {
        return db.queryForList("SELECT id FROM web_checkout_order WHERE tenant_id=? AND contact_id=?", UUID.class, tenant, contact);
    }

    public void markMerged(UUID tenant, UUID merged, UUID survivor) {
        db.update("UPDATE contact SET merged_into=?, updated_at=now() WHERE tenant_id=? AND id=?", survivor, tenant, merged);
        db.update("UPDATE contact SET merged_into=? WHERE tenant_id=? AND merged_into=?", survivor, tenant, merged);
        db.update("UPDATE customer_session SET contact_id=? WHERE tenant_id=? AND contact_id=?", survivor, tenant, merged);
    }

    /** Pasa la cuenta de Google del contacto absorbido al sobreviviente (el absorbido ya está marcado como fusionado). */
    public void moveGoogle(UUID tenant, UUID from, UUID to) {
        db.update("""
                UPDATE contact t SET google_sub = f.google_sub FROM contact f
                 WHERE t.tenant_id = ? AND t.id = ? AND t.google_sub IS NULL AND f.tenant_id = t.tenant_id AND f.id = ?
                """, tenant, to, from);
    }

    public void moveOrders(UUID tenant, List<UUID> orders, UUID contact) {
        if (orders.isEmpty()) return;
        db.update("UPDATE web_checkout_order SET contact_id=? WHERE tenant_id=? AND id = ANY(?)", ps -> {
            ps.setObject(1, contact);
            ps.setObject(2, tenant);
            ps.setArray(3, ps.getConnection().createArrayOf("uuid", orders.toArray()));
        });
    }

    public UUID logMerge(UUID tenant, UUID survivor, UUID merged, String survivorBefore, List<UUID> moved, String by) {
        return db.query(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO contact_merge_log(tenant_id, survivor_id, merged_id, survivor_before, moved_orders, reason, merged_by)
                    VALUES (?,?,?,CAST(? AS jsonb),?,'MANUAL',?) RETURNING id
                    """);
            ps.setObject(1, tenant);
            ps.setObject(2, survivor);
            ps.setObject(3, merged);
            ps.setString(4, survivorBefore);
            ps.setArray(5, con.createArrayOf("uuid", moved.toArray()));
            ps.setString(6, by);
            return ps;
        }, rs -> {
            rs.next();
            return rs.getObject(1, UUID.class);
        });
    }

    public record MergeEntry(UUID id, UUID survivor, UUID merged, String survivorBefore, List<UUID> moved, Instant revertedAt) {}

    public Optional<MergeEntry> mergeEntry(UUID tenant, UUID id) {
        return db.query("SELECT * FROM contact_merge_log WHERE tenant_id=? AND id=? FOR UPDATE", (rs, n) -> new MergeEntry(
                rs.getObject("id", UUID.class), rs.getObject("survivor_id", UUID.class), rs.getObject("merged_id", UUID.class),
                rs.getString("survivor_before"), uuids(rs.getArray("moved_orders")), instant(rs.getTimestamp("reverted_at"))),
                tenant, id).stream().findFirst();
    }

    /** Devuelve el sobreviviente a como estaba antes de la fusión y reactiva el contacto absorbido. */
    public void revert(UUID tenant, MergeEntry entry, String by) {
        db.update("""
                UPDATE contact c SET full_name = b->>'full_name', phone = b->>'phone', email = b->>'email',
                       google_sub = b->>'google_sub', updated_at = now()
                  FROM (SELECT CAST(? AS jsonb) AS b) s WHERE c.tenant_id = ? AND c.id = ?
                """, entry.survivorBefore(), tenant, entry.survivor());
        db.update("UPDATE contact SET merged_into=NULL, updated_at=now() WHERE tenant_id=? AND id=?", tenant, entry.merged());
        moveOrders(tenant, entry.moved(), entry.merged());
        db.update("UPDATE contact_merge_log SET reverted_at=now(), reverted_by=? WHERE tenant_id=? AND id=?", by, tenant, entry.id());
    }

    /** Una fusión solo se deshace si es la última vigente que tocó a esos contactos (si no, el estado ya cambió). */
    public boolean isLatestFor(UUID tenant, MergeEntry entry) {
        return Boolean.FALSE.equals(db.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM contact_merge_log l WHERE l.tenant_id=? AND l.reverted_at IS NULL AND l.id <> ?
                    AND l.merged_at >= (SELECT merged_at FROM contact_merge_log WHERE tenant_id=? AND id=?)
                    AND (l.survivor_id IN (?, ?) OR l.merged_id IN (?, ?)))
                """, Boolean.class, tenant, entry.id(), tenant, entry.id(), entry.survivor(), entry.merged(),
                entry.survivor(), entry.merged()));
    }

    public List<Merge> merges(UUID tenant, UUID contact, int limit) {
        return db.query("""
                SELECT l.id, l.survivor_id, l.merged_id, coalesce(m.full_name, m.phone, m.email) AS merged_name,
                       cardinality(l.moved_orders) AS moved, l.merged_by, l.merged_at, l.reverted_at
                  FROM contact_merge_log l JOIN contact m ON m.tenant_id = l.tenant_id AND m.id = l.merged_id
                 WHERE l.tenant_id = ? AND (CAST(? AS uuid) IS NULL OR l.survivor_id = ? OR l.merged_id = ?)
                 ORDER BY l.merged_at DESC LIMIT ?
                """, (rs, n) -> new Merge(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getString(4), rs.getInt(5), rs.getString(6), instant(rs.getTimestamp(7)), instant(rs.getTimestamp(8))),
                tenant, contact, contact, contact, limit);
    }

    private static List<UUID> uuids(Array array) throws java.sql.SQLException {
        return array == null ? List.of() : Arrays.stream((Object[]) array.getArray()).map(UUID.class::cast).toList();
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
