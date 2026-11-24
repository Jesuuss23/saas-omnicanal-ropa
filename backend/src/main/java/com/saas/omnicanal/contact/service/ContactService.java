package com.saas.omnicanal.contact.service;

import com.saas.omnicanal.checkout.service.OrderTrackingTokens;
import com.saas.omnicanal.contact.model.Contact;
import com.saas.omnicanal.contact.model.ContactViews.Detail;
import com.saas.omnicanal.contact.model.ContactViews.Duplicate;
import com.saas.omnicanal.contact.model.ContactViews.Merge;
import com.saas.omnicanal.contact.model.ContactViews.Order;
import com.saas.omnicanal.contact.model.ContactViews.Row;
import com.saas.omnicanal.contact.repository.ContactRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reglas de identidad del contacto:
 * <ul>
 *   <li>Solo se une automáticamente por datos verificados (cuenta de Google, correo verificado por Google).</li>
 *   <li>El teléfono escrito en el checkout no prueba identidad: crea o reutiliza el contacto de ese número, pero nunca
 *       se suma a la cuenta de otra persona si ya pertenece a otro contacto. Ese caso aparece como posible duplicado.</li>
 *   <li>Toda fusión manual queda en {@code contact_merge_log} y se puede deshacer.</li>
 * </ul>
 */
@Service
public class ContactService {
    public static final int PAGE_SIZE = 25;

    private final ContactRepository contacts;

    public ContactService(ContactRepository contacts) {
        this.contacts = contacts;
    }

    /**
     * Asocia un pedido web recién creado a su contacto, dentro de la transacción del pedido. Con sesión de comprador
     * el pedido va a su cuenta; sin sesión, al contacto del teléfono que escribió.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID attachOrder(UUID tenant, UUID order, UUID signedInContact) {
        var guest = contacts.orderGuest(tenant, order).orElseThrow();
        String name = guest[0];
        String phone = Phones.normalize(guest[1]);
        UUID contact;
        if (signedInContact != null) {
            contact = signedInContact;
            if (phone != null && contacts.activeByPhone(tenant, phone).isEmpty())
                contacts.fillMissing(tenant, contact, name, phone, null, null);
            else contacts.touch(tenant, contact, null);
        } else if (phone != null) {
            contact = byPhone(tenant, phone, name).id();
        } else {
            return null;
        }
        contacts.linkOrder(tenant, order, contact);
        return contact;
    }

    private Contact byPhone(UUID tenant, String phone, String name) {
        var existing = contacts.activeByPhone(tenant, phone);
        if (existing.isPresent()) {
            contacts.touch(tenant, existing.get().id(), blankToNull(name));
            return existing.get();
        }
        return contacts.insert(tenant, blankToNull(name), phone, null, null)
                .or(() -> contacts.activeByPhone(tenant, phone))
                .orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<Row> list(UUID tenant, String query, int page) {
        if (page < 0 || query.length() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return contacts.list(tenant, query.strip(), PAGE_SIZE + 1, (long) page * PAGE_SIZE);
    }

    @Transactional(readOnly = true)
    public Detail detail(UUID tenant, UUID id) {
        Contact contact = contacts.find(tenant, id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        boolean whatsapp = contacts.row(tenant, id).map(Row::whatsapp).orElse(false);
        return new Detail(contact, whatsapp, orders(tenant, id, 50), contacts.merges(tenant, id, 20));
    }

    public List<Order> orders(UUID tenant, UUID contact, int limit) {
        return contacts.orders(tenant, contact, limit).stream().map(o -> new Order(o.id(), o.createdAt(), o.status(), o.total(),
                o.method(), "/tienda/" + tenant + "/pedido/" + o.id() + "#" + OrderTrackingTokens.token(tenant, o.id(), o.reservation())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Duplicate> duplicates(UUID tenant) {
        List<Duplicate> out = new ArrayList<>();
        for (UUID[] pair : contacts.duplicatePairs(tenant, 20)) {
            var a = contacts.row(tenant, pair[0]);
            var b = contacts.row(tenant, pair[1]);
            if (a.isPresent() && b.isPresent())
                out.add(new Duplicate(a.get(), b.get(), "Compró con el teléfono " + (a.get().phone() != null ? a.get().phone() : b.get().phone())));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<Merge> merges(UUID tenant) {
        return contacts.merges(tenant, null, 30);
    }

    /** Une {@code merged} en {@code survivor}: el sobreviviente conserva sus datos y completa los que le falten. */
    @Transactional
    public Merge merge(UUID tenant, UUID survivorId, UUID mergedId, String by) {
        if (survivorId.equals(mergedId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Contact survivor = active(tenant, survivorId);
        Contact merged = active(tenant, mergedId);
        String before = contacts.snapshot(tenant, survivorId);
        List<UUID> moved = contacts.orderIds(tenant, mergedId);
        // Primero se marca como fusionado (libera sus índices únicos) y luego el sobreviviente toma lo que le falta.
        contacts.markMerged(tenant, mergedId, survivorId);
        contacts.moveOrders(tenant, moved, survivorId);
        contacts.fillMissing(tenant, survivorId, merged.fullName(), merged.phone(), merged.email(), null);
        copyGoogle(tenant, survivor, mergedId);
        UUID log = contacts.logMerge(tenant, survivorId, mergedId, before, moved, by);
        return contacts.merges(tenant, survivorId, 50).stream().filter(m -> m.id().equals(log)).findFirst().orElseThrow();
    }

    private void copyGoogle(UUID tenant, Contact survivor, UUID mergedId) {
        if (survivor.google()) return;
        // La cuenta de Google del absorbido pasa al sobreviviente para que el comprador siga viendo sus pedidos.
        contacts.moveGoogle(tenant, mergedId, survivor.id());
    }

    @Transactional
    public void revert(UUID tenant, UUID logId, String by) {
        var entry = contacts.mergeEntry(tenant, logId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (entry.revertedAt() != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "La fusión ya se deshizo.");
        if (!contacts.isLatestFor(tenant, entry))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Primero deshaz las fusiones posteriores de estos contactos.");
        contacts.revert(tenant, entry, by);
    }

    private Contact active(UUID tenant, UUID id) {
        Contact c = contacts.find(tenant, id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (c.mergedInto() != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Ese contacto ya fue fusionado.");
        return c;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }
}
