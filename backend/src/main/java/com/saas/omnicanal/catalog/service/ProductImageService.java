package com.saas.omnicanal.catalog.service;

import com.saas.omnicanal.catalog.repository.ProductImageRepository;
import com.saas.omnicanal.catalog.repository.ProductImageRepository.ImageRef;
import com.saas.omnicanal.shared.media.ImageUpload;
import com.saas.omnicanal.shared.storage.MediaStorage;
import com.saas.omnicanal.shared.storage.StoredFile;
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
    private final MediaStorage storage;

    public ProductImageService(ProductImageRepository images, MediaStorage storage) {
        this.images = images;
        this.storage = storage;
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
        StoredFile stored = null;
        if (storage.enabled()) {
            try {
                stored = storage.upload(tenant, MediaStorage.Folder.PRODUCTS, upload.content(), upload.mediaType(), null);
            } catch (MediaStorage.StorageException e) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo guardar la foto. Inténtalo otra vez.");
            }
            storage.deleteOnRollback(stored);
        }
        images.insert(tenant, product, current.size(), upload.mediaType(), upload.content(), stored);
        return images.list(tenant, product);
    }

    @Transactional
    public List<ImageRef> delete(UUID tenant, UUID product, UUID image) {
        requireProduct(tenant, product);
        images.lockProduct(tenant, product);
        var stored = images.storage(tenant, product, image);
        if (!images.delete(tenant, product, image)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        stored.ifPresent(s -> storage.deleteAfterCommit(s.publicId(), s.resourceType()));
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
