package com.saas.omnicanal.tenant.controller;

import static com.saas.omnicanal.shared.security.OwnerAccess.requireOwner;

import com.saas.omnicanal.shared.media.ImageUpload;
import com.saas.omnicanal.tenant.model.Appearance;
import com.saas.omnicanal.tenant.repository.AppearanceRepository;
import com.saas.omnicanal.tenant.service.AppearanceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
class StoreAppearanceController {
    private final AppearanceService service;
    private final AppearanceRepository appearance;

    StoreAppearanceController(AppearanceService service, AppearanceRepository appearance) {
        this.service = service;
        this.appearance = appearance;
    }

    record Input(@NotBlank @Size(max = 100) String name, @Size(max = 140) String tagline, @Size(max = 1000) String description,
                 @NotBlank @Pattern(regexp = "#[0-9a-fA-F]{6}") String primaryColor, @Size(max = 20) String whatsappPhone,
                 @Pattern(regexp = "@?[A-Za-z0-9._]{0,30}") String instagram, @PositiveOrZero long revision) {}

    @GetMapping("/api/owner/stores/{store}/appearance")
    Appearance get(@PathVariable UUID store, Principal principal) {
        requireOwner(store, principal);
        return service.get(store);
    }

    @PutMapping("/api/owner/stores/{store}/appearance")
    Appearance update(@PathVariable UUID store, Principal principal, @Valid @RequestBody Input in) {
        requireOwner(store, principal);
        return service.update(store, new AppearanceService.Changes(in.name(), in.tagline(), in.description(), in.primaryColor(),
                in.whatsappPhone(), in.instagram(), in.revision()));
    }

    @PostMapping(value = "/api/owner/stores/{store}/appearance/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Appearance logo(@PathVariable UUID store, Principal principal, @RequestParam("file") MultipartFile file) {
        requireOwner(store, principal);
        return service.setLogo(store, ImageUpload.read(file, AppearanceService.MAX_LOGO_BYTES));
    }

    @DeleteMapping("/api/owner/stores/{store}/appearance/logo")
    Appearance removeLogo(@PathVariable UUID store, Principal principal) {
        requireOwner(store, principal);
        return service.removeLogo(store);
    }

    /** El enlace lleva ?v=logoVersion, así el navegador puede guardar el logo un día entero. */
    @GetMapping("/api/public/stores/{store}/logo")
    ResponseEntity<byte[]> publicLogo(@PathVariable UUID store) {
        var logo = appearance.logo(store).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .contentType(MediaType.parseMediaType(logo.mediaType())).header("X-Content-Type-Options", "nosniff")
                .body(logo.content());
    }
}
