package com.saas.omnicanal.shared.media;

import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Imagen subida y validada por su contenido real (firma del archivo), no por la extensión ni el Content-Type. */
public record ImageUpload(String mediaType, byte[] content) {

    public static ImageUpload read(MultipartFile file, int maxBytes) {
        if (file == null || file.isEmpty()) throw bad("Elige una imagen.");
        if (file.getSize() > maxBytes) throw bad("La imagen supera " + (maxBytes / 1024 >= 1024 ? maxBytes / 1048576 + " MB" : maxBytes / 1024 + " KB") + ".");
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw bad("No se pudo leer la imagen.");
        }
        String type = sniff(bytes);
        if (type == null) throw bad("Usa una imagen JPG, PNG o WebP.");
        return new ImageUpload(type, bytes);
    }

    static String sniff(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return null;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
