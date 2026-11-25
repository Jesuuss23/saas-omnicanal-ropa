package com.saas.omnicanal.inbox.controller;

import com.saas.omnicanal.inbox.model.InboxViews.Conversation;
import com.saas.omnicanal.inbox.model.InboxViews.Counts;
import com.saas.omnicanal.inbox.model.InboxViews.Row;
import com.saas.omnicanal.inbox.model.InboxViews.Status;
import com.saas.omnicanal.inbox.service.InboxService;
import com.saas.omnicanal.team.model.StoreSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import static com.saas.omnicanal.shared.security.OwnerAccess.requireOwner;

/** Bandeja de conversaciones de WhatsApp para todo el equipo de la tienda. El chat va en el cuerpo o query (tiene "@"). */
@RestController
@RequestMapping("/api/owner/stores/{store}/inbox/conversations")
class InboxController {
    private final InboxService inbox;

    InboxController(InboxService inbox) {
        this.inbox = inbox;
    }

    record Handback(@Size(max = 1000) String note) {}
    record ChangeStatus(@NotNull Status status) {}
    record ChangeAi(@NotNull Boolean enabled) {}

    @GetMapping
    List<Row> list(@PathVariable UUID store, Authentication auth, @RequestParam(required = false) Status status,
                   @RequestParam(defaultValue = "") String query) {
        requireOwner(store, auth);
        return inbox.list(store, status, query);
    }

    @GetMapping("/counts")
    Counts counts(@PathVariable UUID store, Authentication auth) {
        requireOwner(store, auth);
        return inbox.counts(store);
    }

    @GetMapping("/detail")
    Conversation detail(@PathVariable UUID store, Authentication auth, @RequestParam String chat) {
        requireOwner(store, auth);
        return inbox.conversation(store, chat);
    }

    @PostMapping("/take")
    Conversation take(@PathVariable UUID store, Authentication auth, @RequestParam String chat) {
        requireOwner(store, auth);
        return inbox.take(store, chat, actor(auth));
    }

    @PostMapping("/handback")
    Conversation handback(@PathVariable UUID store, Authentication auth, @RequestParam String chat, @Valid @RequestBody Handback in) {
        requireOwner(store, auth);
        return inbox.handback(store, chat, in.note());
    }

    @PutMapping("/status")
    Conversation status(@PathVariable UUID store, Authentication auth, @RequestParam String chat, @Valid @RequestBody ChangeStatus in) {
        requireOwner(store, auth);
        return inbox.setStatus(store, chat, in.status());
    }

    @PutMapping("/ai")
    Conversation ai(@PathVariable UUID store, Authentication auth, @RequestParam String chat, @Valid @RequestBody ChangeAi in) {
        requireOwner(store, auth);
        return inbox.setAi(store, chat, in.enabled());
    }

    private static String actor(Authentication auth) {
        return auth.getDetails() instanceof StoreSession s && s.email() != null ? s.email() : "propietario";
    }
}
