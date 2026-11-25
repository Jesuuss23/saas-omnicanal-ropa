package com.saas.omnicanal.media;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** Parte de archivo para un multipart/form-data con nombre y tipo. */
final class Multipart {
    private Multipart() {}

    static HttpEntity<ByteArrayResource> file(byte[] content, String fileName, String mimeType) {
        var headers = new HttpHeaders();
        try {
            headers.setContentType(MediaType.parseMediaType(mimeType == null ? "application/octet-stream" : mimeType));
        } catch (RuntimeException invalid) {
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        }
        return new HttpEntity<>(new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        }, headers);
    }

    static String extension(String mimeType) {
        String m = mimeType == null ? "" : mimeType.toLowerCase();
        if (m.contains("ogg") || m.contains("opus")) return "ogg";
        if (m.contains("mpeg") || m.contains("mp3")) return "mp3";
        if (m.contains("mp4") || m.contains("m4a") || m.contains("aac")) return "m4a";
        if (m.contains("wav")) return "wav";
        if (m.contains("webm")) return "webm";
        if (m.contains("png")) return "png";
        if (m.contains("webp")) return "webp";
        return m.startsWith("image/") ? "jpg" : "bin";
    }
}
