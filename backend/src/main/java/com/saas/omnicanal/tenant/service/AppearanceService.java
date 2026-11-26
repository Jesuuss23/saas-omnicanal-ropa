package com.saas.omnicanal.tenant.service;

import com.saas.omnicanal.shared.media.ImageUpload;
import com.saas.omnicanal.shared.storage.MediaStorage;
import com.saas.omnicanal.shared.storage.StoredFile;
import com.saas.omnicanal.tenant.model.Appearance;
import com.saas.omnicanal.tenant.repository.AppearanceRepository;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AppearanceService {
    public static final int MAX_LOGO_BYTES = 512 * 1024;

    private final AppearanceRepository appearance;
    private final MediaStorage storage;

    public AppearanceService(AppearanceRepository appearance, MediaStorage storage) {
        this.appearance = appearance;
        this.storage = storage;
    }

    public record Changes(String name, String tagline, String description, String primaryColor, String whatsappPhone,
                          String instagram, long revision) {}

    public Appearance get(UUID tenant) {
        return appearance.find(tenant).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional
    public Appearance update(UUID tenant, Changes c) {
        appearance.ensureRow(tenant);
        String phone = blank(c.whatsappPhone()) == null ? null : c.whatsappPhone().replaceAll("[^0-9]", "");
        if (phone != null && !phone.matches("[0-9]{8,15}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El WhatsApp debe tener entre 8 y 15 dígitos, con código de país.");
        String instagram = blank(c.instagram()) == null ? null : c.instagram().strip().replaceFirst("^@", "");
        long revision = c.revision() == 0 ? get(tenant).revision() : c.revision();
        if (!appearance.update(tenant, revision, blank(c.tagline()), blank(c.description()),
                c.primaryColor().toLowerCase(Locale.ROOT), phone, instagram))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Otra persona cambió la apariencia. Recarga e inténtalo otra vez.");
        appearance.renameStore(tenant, c.name().strip());
        return get(tenant);
    }

    @Transactional
    public Appearance setLogo(UUID tenant, ImageUpload logo) {
        appearance.ensureRow(tenant);
        StoredFile stored = null;
        if (storage.enabled()) {
            try {
                stored = storage.upload(tenant, MediaStorage.Folder.LOGO, logo.content(), logo.mediaType(), null);
            } catch (MediaStorage.StorageException e) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo guardar el logo. Inténtalo otra vez.");
            }
            storage.deleteOnRollback(stored);
        }
        appearance.setLogo(tenant, logo.mediaType(), logo.content(), stored)
                .ifPresent(old -> storage.deleteAfterCommit(old.publicId(), old.resourceType()));
        return get(tenant);
    }

    @Transactional
    public Appearance removeLogo(UUID tenant) {
        appearance.ensureRow(tenant);
        appearance.setLogo(tenant, null, null, null)
                .ifPresent(old -> storage.deleteAfterCommit(old.publicId(), old.resourceType()));
        return get(tenant);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
