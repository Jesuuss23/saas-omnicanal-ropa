package com.saas.omnicanal.contact.controller;

import com.saas.omnicanal.contact.model.ContactViews.Detail;
import com.saas.omnicanal.contact.model.ContactViews.Duplicate;
import com.saas.omnicanal.contact.model.ContactViews.Merge;
import com.saas.omnicanal.contact.model.ContactViews.Row;
import com.saas.omnicanal.contact.service.ContactService;
import com.saas.omnicanal.team.model.StoreSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import static com.saas.omnicanal.shared.security.OwnerAccess.requireOwner;

/** Contactos unificados de la tienda. Fusionar y deshacer quedan para propietario y encargado (ver OwnerSecurity). */
@RestController
@RequestMapping("/api/owner/stores/{store}/contacts")
class OwnerContactsController {
    private final ContactService contacts;

    OwnerContactsController(ContactService contacts) {
        this.contacts = contacts;
    }

    record Page(List<Row> rows, boolean hasNext) {}
    record MergeRequest(@NotNull UUID mergedId) {}

    @GetMapping
    Page list(@PathVariable UUID store, Principal principal, @RequestParam(defaultValue = "") String query,
              @RequestParam(defaultValue = "0") int page) {
        requireOwner(store, principal);
        var rows = contacts.list(store, query, page);
        return new Page(rows.stream().limit(ContactService.PAGE_SIZE).toList(), rows.size() > ContactService.PAGE_SIZE);
    }

    @GetMapping("/{contact}")
    Detail detail(@PathVariable UUID store, @PathVariable UUID contact, Principal principal) {
        requireOwner(store, principal);
        return contacts.detail(store, contact);
    }

    @GetMapping("/duplicates")
    List<Duplicate> duplicates(@PathVariable UUID store, Principal principal) {
        requireOwner(store, principal);
        return contacts.duplicates(store);
    }

    @GetMapping("/merges")
    List<Merge> merges(@PathVariable UUID store, Principal principal) {
        requireOwner(store, principal);
        return contacts.merges(store);
    }

    @PostMapping("/{contact}/merge")
    Merge merge(@PathVariable UUID store, @PathVariable UUID contact, Authentication auth, @Valid @RequestBody MergeRequest in) {
        requireOwner(store, auth);
        return contacts.merge(store, contact, in.mergedId(), actor(auth));
    }

    @PostMapping("/merges/{merge}/revert")
    void revert(@PathVariable UUID store, @PathVariable UUID merge, Authentication auth) {
        requireOwner(store, auth);
        contacts.revert(store, merge, actor(auth));
    }

    private static String actor(Authentication auth) {
        return auth.getDetails() instanceof StoreSession s && s.email() != null ? s.email() : "propietario";
    }
}
