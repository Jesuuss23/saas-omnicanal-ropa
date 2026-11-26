package com.saas.omnicanal.shared.storage;

import java.util.Locale;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Almacenamiento central de archivos que no son texto (imágenes, audios, videos y documentos). La implementación de
 * producción es Cloudinary; sin credenciales {@link #enabled()} es false y cada módulo guarda el contenido en la base
 * como antes (desarrollo local y pruebas).
 */
public interface MediaStorage {

    /** Carpetas dentro de {@code {raíz}/{storeId}/}. */
    enum Folder {
        PRODUCTS("products"), LOGO("logo"), CATALOGS("catalogs"), WHATSAPP_INBOUND("whatsapp/inbound"),
        WHATSAPP_OUTBOUND("whatsapp/outbound");

        public final String path;

        Folder(String path) {
            this.path = path;
        }
    }

    boolean enabled();

    /** Sube el archivo a {@code {raíz}/{store}/{folder}} con un nombre aleatorio. Lanza {@link StorageException} si falla. */
    StoredFile upload(UUID store, Folder folder, byte[] content, String mimeType, String fileName);

    /** Borra el archivo (mejor esfuerzo: un fallo solo se registra). */
    void delete(String publicId, String resourceType);

    /** Descarga un archivo guardado (solo URLs de esta cuenta de Cloudinary). */
    byte[] download(String url);

    /** Contenido de un archivo: el guardado en la base si existe; si no, se descarga de Cloudinary. */
    default byte[] load(byte[] content, String url) {
        if (content != null) return content;
        if (url == null) throw new StorageException("Archivo sin contenido ni URL", null);
        return download(url);
    }

    /** Si la transacción actual se revierte, borra el archivo recién subido para no dejar huérfanos. */
    default void deleteOnRollback(StoredFile file) {
        if (file == null || !TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) delete(file.publicId(), file.resourceType());
            }
        });
    }

    /** Borra el archivo cuando la transacción confirma el borrado de su fila (o en el acto si no hay transacción). */
    default void deleteAfterCommit(String publicId, String resourceType) {
        if (publicId == null) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            delete(publicId, resourceType);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                delete(publicId, resourceType);
            }
        });
    }

    /** Tipo de recurso de Cloudinary: {@code image}, {@code video} (también audios) o {@code raw} (PDF y documentos). */
    static String resourceType(String mimeType) {
        String m = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (m.startsWith("image/") && !m.equals("image/svg+xml")) return "image";
        if (m.startsWith("video/") || m.startsWith("audio/")) return "video";
        return "raw";
    }

    class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
