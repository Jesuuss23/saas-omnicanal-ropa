package com.saas.omnicanal.catalog.service;

import com.saas.omnicanal.catalog.repository.ProductImageRepository;
import com.saas.omnicanal.catalog.repository.ProductImageRepository.ImageRef;
import com.saas.omnicanal.shared.media.ImageUpload;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Fotos de producto: hasta 8 por producto, la primera es la portada en el catálogo. */
@Service
public class ProductImageService {
    public static final int MAX_IMAGES = 8;
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    private final ProductImageRepository images;

    public ProductImageService(ProductImageRepository images) {
        this.images = images;
    }

    public List<ImageRef> list(UUID tenant, UUID product) {
        requireProduct(tenant, product);
        return images.list(tenant, product);
    }

    @Transactional
    public List<ImageRef> add(UUID tenant, UUID product, ImageUpload upload) {
        requireProduct(tenant, product);
        images.lockProduct(tenant, product);
        var current = images.list(tenant, product);
        if (current.size() >= MAX_IMAGES)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cada producto admite hasta " + MAX_IMAGES + " fotos.");
        images.insert(tenant, product, current.size(), upload.mediaType(), upload.content());
        return images.list(tenant, product);
    }

    @Transactional
    public List<ImageRef> delete(UUID tenant, UUID product, UUID image) {
        requireProduct(tenant, product);
        images.lockProduct(tenant, product);
        if (!images.delete(tenant, product, image)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        compact(tenant, product, images.list(tenant, product).stream().map(ImageRef::id).toList());
        return images.list(tenant, product);
    }

    /** Reordena con la lista completa de ids; el primero queda como portada. */
    @Transactional
    public List<ImageRef> reorder(UUID tenant, UUID product, List<UUID> order) {
        requireProduct(tenant, product);
        images.lockProduct(tenant, product);
        var current = images.list(tenant, product).stream().map(ImageRef::id).toList();
        if (order.size() != current.size() || !new HashSet<>(order).equals(new HashSet<>(current)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Las fotos cambiaron. Recarga e inténtalo otra vez.");
        compact(tenant, product, order);
        return images.list(tenant, product);
    }

    private void compact(UUID tenant, UUID product, List<UUID> order) {
        for (int i = 0; i < order.size(); i++) images.setPosition(tenant, product, order.get(i), i);
    }

    private void requireProduct(UUID tenant, UUID product) {
        if (!images.productExists(tenant, product)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
