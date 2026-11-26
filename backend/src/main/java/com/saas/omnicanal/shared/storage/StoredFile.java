package com.saas.omnicanal.shared.storage;

/**
 * Archivo guardado en Cloudinary: {@code url} es la {@code secure_url} (https) que se devuelve al frontend y se guarda en
 * la base; {@code publicId} y {@code resourceType} sirven para borrarlo.
 */
public record StoredFile(String url, String publicId, String resourceType, int bytes) {}
