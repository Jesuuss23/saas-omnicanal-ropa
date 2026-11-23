package com.saas.omnicanal.catalog.controller;

import static com.saas.omnicanal.shared.security.OwnerAccess.requireOwner;

import com.saas.omnicanal.catalog.repository.ProductImageRepository;
import com.saas.omnicanal.catalog.repository.ProductImageRepository.ImageRef;
import com.saas.omnicanal.catalog.service.ProductImageService;
import com.saas.omnicanal.shared.media.ImageUpload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
class ProductImageController {
    private final ProductImageService service;
    private final ProductImageRepository images;

    ProductImageController(ProductImageService service, ProductImageRepository images) {
        this.service = service;
        this.images = images;
    }

    private static final String BASE = "/api/owner/stores/{store}/catalog/products/{product}/images";

    record Order(@NotNull @Size(max = ProductImageService.MAX_IMAGES) List<@NotNull UUID> ids) {}

    @GetMapping(BASE)
    List<ImageRef> list(@PathVariable UUID store, @PathVariable UUID product, Principal principal) {
        requireOwner(store, principal);
        return service.list(store, product);
    }

    @PostMapping(value = BASE, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    List<ImageRef> upload(@PathVariable UUID store, @PathVariable UUID product, Principal principal,
                          @RequestParam("file") MultipartFile file) {
        requireOwner(store, principal);
        return service.add(store, product, ImageUpload.read(file, ProductImageService.MAX_BYTES));
    }

    @PutMapping(BASE + "/order")
    List<ImageRef> reorder(@PathVariable UUID store, @PathVariable UUID product, Principal principal, @Valid @RequestBody Order order) {
        requireOwner(store, principal);
        return service.reorder(store, product, order.ids());
    }

    @DeleteMapping(BASE + "/{image}")
    List<ImageRef> delete(@PathVariable UUID store, @PathVariable UUID product, @PathVariable UUID image, Principal principal) {
        requireOwner(store, principal);
        return service.delete(store, product, image);
    }

    /** Las fotos no cambian (otra foto = otro id), así se pueden cachear por mucho tiempo. */
    @GetMapping("/api/public/stores/{store}/images/{image}")
    ResponseEntity<byte[]> read(@PathVariable UUID store, @PathVariable UUID image) {
        var data = images.read(store, image).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .contentType(MediaType.parseMediaType(data.mediaType())).header("X-Content-Type-Options", "nosniff")
                .body(data.content());
    }
}
