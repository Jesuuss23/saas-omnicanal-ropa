package com.saas.omnicanal.whatsapp.controller;

import static com.saas.omnicanal.shared.security.OwnerAccess.requireOwner;

import com.saas.omnicanal.whatsapp.model.InboundMessage;
import com.saas.omnicanal.whatsapp.model.ManualReply;
import com.saas.omnicanal.whatsapp.model.WhatsAppProvider;
import com.saas.omnicanal.whatsapp.repository.WhatsAppChannelSettingRepository;
import com.saas.omnicanal.whatsapp.repository.WhatsAppMessageRepository;
import com.saas.omnicanal.whatsapp.service.EvolutionApiClient;
import com.saas.omnicanal.whatsapp.service.EvolutionConnectionService;
import com.saas.omnicanal.whatsapp.service.EvolutionConnectionService.Connection;
import com.saas.omnicanal.whatsapp.service.WhatsAppReplyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * WhatsApp del dueño de tienda. Las rutas raíz (/whatsapp, /connect, /inbox/enable) son la categoría
 * Evolution API (QR); Cloud API vive en {@link OwnerCloudApiController}. Inbox y respuestas son comunes.
 */
@RestController
@RequestMapping("/api/owner/stores/{store}/whatsapp")
class OwnerWhatsAppController {
    private final EvolutionConnectionService evolution;
    private final EvolutionApiClient evolutionClient;
    private final WhatsAppChannelSettingRepository channels;
    private final WhatsAppMessageRepository messages;
    private final WhatsAppReplyService replies;

    OwnerWhatsAppController(EvolutionConnectionService evolution, EvolutionApiClient evolutionClient,
                            WhatsAppChannelSettingRepository channels, WhatsAppMessageRepository messages,
                            WhatsAppReplyService replies) {
        this.evolution = evolution;
        this.evolutionClient = evolutionClient;
        this.channels = channels;
        this.messages = messages;
        this.replies = replies;
    }

    record ChannelSettings(WhatsAppProvider activeProvider, boolean evolutionAvailable) {}
    record ChannelInput(@NotNull WhatsAppProvider activeProvider) {}

    @GetMapping("/channel")
    ChannelSettings channel(@PathVariable UUID store, Principal owner) {
        requireOwner(store, owner);
        return new ChannelSettings(channels.activeProvider(store), evolutionClient.configured());
    }

    /** Elige por qué proveedor salen las respuestas del inbox. El historial se conserva al cambiar. */
    @PutMapping("/channel")
    ChannelSettings setChannel(@PathVariable UUID store, Principal owner, @Valid @RequestBody ChannelInput input) {
        requireOwner(store, owner);
        channels.setActiveProvider(store, input.activeProvider());
        return channel(store, owner);
    }

    @GetMapping
    ResponseEntity<Connection> status(@PathVariable UUID store, Principal owner) {
        requireOwner(store, owner);
        return noStore(evolution.status(store));
    }

    @PostMapping("/connect")
    ResponseEntity<Connection> connect(@PathVariable UUID store, Principal owner) {
        requireOwner(store, owner);
        return noStore(evolution.connect(store));
    }

    @PostMapping("/inbox/enable")
    Map<String, Boolean> enable(@PathVariable UUID store, Principal owner) {
        requireOwner(store, owner);
        evolution.enableInbox(store);
        return Map.of("enabled", true);
    }

    @GetMapping("/inbox")
    ResponseEntity<List<InboundMessage>> inbox(@PathVariable UUID store, Principal owner) {
        requireOwner(store, owner);
        return noStore(messages.recentInbound(store, 20));
    }

    record ReplyInput(@NotBlank @Size(max = 160) String chat, @NotBlank @Size(max = 4000) String text) {}

    @GetMapping("/replies")
    ResponseEntity<List<ManualReply>> replies(@PathVariable UUID store, Principal owner, @RequestParam String chat) {
        requireOwner(store, owner);
        return noStore(replies.replies(store, chat));
    }

    @PutMapping("/replies/{requestId}")
    Map<String, String> reply(@PathVariable UUID store, @PathVariable UUID requestId, Principal owner,
                              @Valid @RequestBody ReplyInput input) {
        requireOwner(store, owner);
        return Map.of("status", replies.reply(store, requestId, input.chat(), input.text()));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
