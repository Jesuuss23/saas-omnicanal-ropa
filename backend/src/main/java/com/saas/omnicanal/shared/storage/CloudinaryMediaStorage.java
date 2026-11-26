package com.saas.omnicanal.shared.storage;

import com.cloudinary.Cloudinary;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Cloudinary con las variables {@code CLOUDINARY_CLOUD_NAME}, {@code CLOUDINARY_API_KEY} y {@code CLOUDINARY_API_SECRET}.
 * Los archivos quedan en {@code {CLOUDINARY_FOLDER}/{storeId}/{carpeta}/{uuid}} con entrega {@code upload} por https.
 */
@Service
public class CloudinaryMediaStorage implements MediaStorage {
    private static final Logger log = LoggerFactory.getLogger(CloudinaryMediaStorage.class);
    /** Mayor archivo que maneja el sistema (WhatsApp, 16 MB) con margen. */
    static final int MAX_DOWNLOAD = 17 * 1024 * 1024;

    private final Cloudinary cloudinary;
    private final String cloudName;
    private final String root;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public CloudinaryMediaStorage(@Value("${storage.cloudinary.cloud-name:}") String cloudName,
                                  @Value("${storage.cloudinary.api-key:}") String apiKey,
                                  @Value("${storage.cloudinary.api-secret:}") String apiSecret,
                                  @Value("${storage.cloudinary.folder:omnicanal}") String root) {
        this.cloudName = cloudName == null ? "" : cloudName.strip();
        this.root = root == null || root.isBlank() ? "omnicanal" : root.strip().replaceAll("^/+|/+$", "");
        boolean configured = !this.cloudName.isEmpty() && apiKey != null && !apiKey.isBlank() && apiSecret != null && !apiSecret.isBlank();
        this.cloudinary = configured ? new Cloudinary(Map.of("cloud_name", this.cloudName, "api_key", apiKey.strip(),
                "api_secret", apiSecret.strip(), "secure", true)) : null;
        if (!configured) log.info("Cloudinary sin configurar: los archivos se guardan en la base de datos.");
    }

    @Override
    public boolean enabled() {
        return cloudinary != null;
    }

    @Override
    public StoredFile upload(UUID store, Folder folder, byte[] content, String mimeType, String fileName) {
        if (!enabled()) throw new IllegalStateException("Cloudinary sin configurar");
        String type = MediaStorage.resourceType(mimeType);
        // En "raw" Cloudinary no agrega la extensión: va en el public_id para que el archivo se descargue con su tipo.
        String publicId = UUID.randomUUID() + ("raw".equals(type) ? extension(mimeType, fileName) : "");
        Map<String, Object> options = new HashMap<>();
        options.put("folder", root + "/" + store + "/" + folder.path);
        options.put("public_id", publicId);
        options.put("resource_type", type);
        options.put("overwrite", false);
        options.put("unique_filename", false);
        options.put("use_filename", false);
        try {
            Map<?, ?> r = cloudinary.uploader().upload(content, options);
            String url = (String) r.get("secure_url");
            if (url == null) throw new IllegalStateException("Cloudinary no devolvió secure_url");
            Object bytes = r.get("bytes");
            return new StoredFile(url, (String) r.get("public_id"), String.valueOf(r.get("resource_type")),
                    bytes instanceof Number n ? n.intValue() : content.length);
        } catch (IOException | RuntimeException e) {
            throw new StorageException("No se pudo subir el archivo a Cloudinary: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String publicId, String resourceType) {
        if (!enabled() || publicId == null) return;
        try {
            cloudinary.uploader().destroy(publicId, Map.of("resource_type", resourceType == null ? "image" : resourceType,
                    "invalidate", true));
        } catch (IOException | RuntimeException e) {
            log.warn("Cloudinary: no se pudo borrar {}: {}", publicId, e.getMessage());
        }
    }

    @Override
    public byte[] download(String url) {
        if (!ownUrl(url)) throw new StorageException("URL de archivo no permitida", null);
        try {
            var response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
                byte[] bytes = body.readNBytes(MAX_DOWNLOAD + 1);
                if (bytes.length > MAX_DOWNLOAD) throw new IOException("archivo demasiado grande");
                return bytes;
            }
        } catch (IOException e) {
            throw new StorageException("No se pudo descargar el archivo de Cloudinary: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StorageException("Descarga interrumpida", e);
        }
    }

    /** Solo se descargan archivos de esta cuenta (evita que una URL guardada apunte a otro servidor). */
    boolean ownUrl(String url) {
        return url != null && !cloudName.isEmpty() && url.startsWith("https://res.cloudinary.com/" + cloudName + "/");
    }

    static String extension(String mimeType, String fileName) {
        String m = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT).split(";")[0].strip();
        String known = switch (m) {
            case "application/pdf" -> ".pdf";
            case "application/msword" -> ".doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
            case "application/vnd.ms-excel" -> ".xls";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx";
            case "application/vnd.ms-powerpoint" -> ".ppt";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> ".pptx";
            case "application/zip" -> ".zip";
            case "text/plain" -> ".txt";
            case "text/csv" -> ".csv";
            default -> null;
        };
        if (known != null) return known;
        if (fileName != null) {
            int dot = fileName.lastIndexOf('.');
            if (dot >= 0) {
                String ext = fileName.substring(dot).toLowerCase(Locale.ROOT);
                if (ext.matches("\\.[a-z0-9]{1,8}")) return ext;
            }
        }
        return "";
    }
}
