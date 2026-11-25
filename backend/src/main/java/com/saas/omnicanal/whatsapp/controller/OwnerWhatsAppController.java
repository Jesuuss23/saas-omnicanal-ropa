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
import com.saas.omnicanal.whatsapp.model.MediaKind;
import com.saas.omnicanal.whatsapp.repository.WhatsAppMediaRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
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
    private final WhatsAppMediaRepository media;

    OwnerWhatsAppController(EvolutionConnectionService evolution, EvolutionApiClient evolutionClient,
                            WhatsAppChannelSettingRepository channels, WhatsAppMessageRepository messages,
                            WhatsAppReplyService replies, WhatsAppMediaRepository media) {
        this.media = media;
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

    /** Si el plan permite enviar imágenes y videos (los PDF siempre se pueden enviar). */
    @GetMapping("/media/capabilities")
    ResponseEntity<Map<String, Boolean>> mediaCapabilities(@PathVariable UUID store, Principal owner) {
        requireOwner(store, owner);
        return noStore(Map.of("send", replies.canSendMedia(store), "documents", true));
    }

    /** Archivo de un mensaje (entrante o enviado). Solo para el equipo de la tienda. */
    @GetMapping("/media/{id}")
    ResponseEntity<byte[]> media(@PathVariable UUID store, @PathVariable UUID id, Principal owner) {
        requireOwner(store, owner);
        var file = media.read(store, id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        MediaType type;
        try {
            type = MediaType.parseMediaType(file.mimeType());
        } catch (RuntimeException invalid) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        var response = ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
                .contentType(type).header("X-Content-Type-Options", "nosniff");
        if (file.kind() == MediaKind.DOCUMENT)
            response = response.header("Content-Disposition", ContentDisposition.attachment()
                    .filename(file.fileName() == null ? "archivo" : file.fileName(), StandardCharsets.UTF_8).build().toString());
        return response.body(file.content());
    }

    /** Envía una imagen, un video o un PDF desde la bandeja. Imágenes y videos dependen del plan. */
    @PostMapping(value = "/replies/{requestId}/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, String> replyMedia(@PathVariable UUID store, @PathVariable UUID requestId, Principal owner,
                                   @RequestParam String chat, @RequestParam(defaultValue = "") String caption,
                                   @RequestParam("file") MultipartFile file) {
        requireOwner(store, owner);
        if (chat.isBlank() || chat.length() > 160 || caption.length() > 1000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El archivo está vacío.");
        if (file.getSize() > WhatsAppMediaRepository.MAX_BYTES)
            throw new ResponseStatusException(HttpStatus.valueOf(413), "El archivo supera 16 MB.");
        byte[] content;
        try {
            content = file.getBytes();
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        String mime = detect(content);
        if (mime == null) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Solo se pueden enviar imágenes JPG, PNG o WebP, videos MP4 o archivos PDF.");
        String name = file.getOriginalFilename();
        name = name == null || name.isBlank() ? null : name.replaceAll("[\\\\/\\r\\n]", "_");
        var outgoing = new WhatsAppReplyService.Outgoing(MediaKind.fromMime(mime), mime, name, content, caption);
        return Map.of("status", replies.replyMedia(store, requestId, chat, outgoing));
    }

    /** Tipo real por los primeros bytes; no confiamos en el nombre ni en el content-type del navegador. */
    static String detect(byte[] b) {
        if (b.length < 12) return null;
        if ((b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) return "image/jpeg";
        if ((b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P')
            return "image/webp";
        if (b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') return "application/pdf";
        if (b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
            String brand = new String(b, 8, 3, StandardCharsets.US_ASCII);
            return brand.startsWith("3g") ? "video/3gpp" : "video/mp4";
        }
        return null;
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
