package com.saas.omnicanal.whatsapp.controller;

import static com.saas.omnicanal.shared.security.OwnerAccess.requireOwner;

import com.saas.omnicanal.whatsapp.model.CloudApiChannel;
import com.saas.omnicanal.whatsapp.model.SendResult;
import com.saas.omnicanal.whatsapp.model.WhatsAppTemplate;
import com.saas.omnicanal.whatsapp.repository.WhatsAppMessageRepository;
import com.saas.omnicanal.whatsapp.service.CloudApiService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Categoría WhatsApp Cloud API (oficial de Meta): credenciales, verificación, plantillas y entorno de prueba. */
@RestController
@RequestMapping("/api/owner/stores/{store}/whatsapp/cloud")
class OwnerCloudApiController {
    private final CloudApiService cloud;

    OwnerCloudApiController(CloudApiService cloud) {
        this.cloud = cloud;
    }

    @GetMapping
    ResponseEntity<CloudApiChannel> get(@PathVariable UUID store, Principal owner) {
        requireOwner(store, owner);
        return noStore(cloud.channel(store));
    }

    record CredentialsInput(@NotBlank @Pattern(regexp = "\\s*[0-9]{5,32}\\s*") String phoneNumberId,
                            @NotBlank @Pattern(regexp = "\\s*[0-9]{5,32}\\s*") String wabaId,
                            @Size(max = 4096) String accessToken,
                            @Size(max = 256) String appSecret,
                            @NotNull @PositiveOrZero Long revision) {
        @Override
        public String toString() {
            return "CredentialsInput[phoneNumberId=" + phoneNumberId + ", wabaId=" + wabaId + "]";
        }
    }

    @PutMapping
    ResponseEntity<CloudApiChannel> save(@PathVariable UUID store, Principal owner, @Valid @RequestBody CredentialsInput input) {
        requireOwner(store, owner);
        return noStore(cloud.save(store, new CloudApiService.CredentialsInput(input.phoneNumberId(), input.wabaId(),
                input.accessToken(), input.appSecret(), input.revision())));
    }

    @DeleteMapping
    ResponseEntity<Void> disconnect(@PathVariable UUID store, Principal owner) {
        requireOwner(store, owner);
        cloud.disconnect(store);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/verify")
    ResponseEntity<CloudApiChannel> verify(@PathVariable UUID store, Principal owner) {
        requireOwner(store, owner);
        return noStore(cloud.verify(store));
    }

    @GetMapping("/templates")
    ResponseEntity<List<WhatsAppTemplate>> templates(@PathVariable UUID store, Principal owner) {
        requireOwner(store, owner);
        return noStore(cloud.templates(store));
    }

    @PostMapping("/templates/sync")
    ResponseEntity<CloudApiService.SyncResult> sync(@PathVariable UUID store, Principal owner) {
        requireOwner(store, owner);
        return noStore(cloud.syncTemplates(store));
    }

    record TestInput(@NotBlank @Size(max = 24) String to,
                     @NotBlank @Pattern(regexp = "TEXT|TEMPLATE") String kind,
                     @Size(max = 4096) String text,
                     @Size(max = 512) String templateName,
                     @Size(max = 16) String language,
                     @Size(max = 20) List<@Size(max = 1024) String> parameters) {}

    @PostMapping("/test-message")
    SendResult test(@PathVariable UUID store, Principal owner, @Valid @RequestBody TestInput input) {
        requireOwner(store, owner);
        return cloud.sendTest(store, new CloudApiService.TestInput(input.to(), input.kind(), input.text(),
                input.templateName(), input.language(), input.parameters()));
    }

    @GetMapping("/test-messages")
    ResponseEntity<List<WhatsAppMessageRepository.TestMessage>> tests(@PathVariable UUID store, Principal owner) {
        requireOwner(store, owner);
        return noStore(cloud.recentTests(store));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
