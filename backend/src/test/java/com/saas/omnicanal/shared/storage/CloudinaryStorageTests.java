package com.saas.omnicanal.shared.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.saas.omnicanal.assistant.model.AssistantModels.Answer;
import com.saas.omnicanal.assistant.repository.AssistantRepository;
import com.saas.omnicanal.assistant.service.AssistantResponder;
import com.saas.omnicanal.inbox.service.InboxService;
import com.saas.omnicanal.media.ModerationClient;
import com.saas.omnicanal.media.TranscriptionClient;
import com.saas.omnicanal.media.VisionClient;
import com.saas.omnicanal.whatsapp.model.SendResult;
import com.saas.omnicanal.whatsapp.repository.WhatsAppMediaRepository;
import com.saas.omnicanal.whatsapp.service.EvolutionApiClient;
import com.saas.omnicanal.whatsapp.service.EvolutionWebhookService;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

/**
 * Con Cloudinary configurado, fotos, logo y multimedia de WhatsApp guardan solo la secure_url en la base; las respuestas
 * la devuelven y los endpoints antiguos redirigen a ella. La moderación sigue recibiendo los bytes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CloudinaryStorageTests {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
    private static final byte[] JPG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, 3, 4, 5, 6, 7, 8, 9};

    /** Cloudinary de mentira: guarda en memoria y responde URLs con la forma real. */
    static class FakeStorage implements MediaStorage {
        final Map<String, byte[]> files = new ConcurrentHashMap<>();
        final List<String> deleted = new CopyOnWriteArrayList<>();

        @Override public boolean enabled() { return true; }

        @Override
        public StoredFile upload(UUID store, Folder folder, byte[] content, String mimeType, String fileName) {
            String type = MediaStorage.resourceType(mimeType);
            String id = "omnicanal/" + store + "/" + folder.path + "/" + UUID.randomUUID();
            String url = "https://res.cloudinary.com/demo/" + type + "/upload/v1/" + id;
            files.put(url, content);
            return new StoredFile(url, id, type, content.length);
        }

        @Override public void delete(String publicId, String resourceType) { deleted.add(publicId); }

        @Override
        public byte[] download(String url) {
            byte[] b = files.get(url);
            if (b == null) throw new StorageException("no existe " + url, null);
            return b;
        }
    }

    @TestConfiguration
    static class Config {
        @Bean @Primary FakeStorage fakeStorage() { return new FakeStorage(); }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry props) {
        props.add("EVOLUTION_WEBHOOK_SECRET", () -> "secreto-de-prueba");
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired FakeStorage storage;
    @Autowired StorageBackfill backfill;
    @Autowired EvolutionWebhookService webhooks;
    @Autowired WhatsAppMediaRepository media;
    @Autowired AssistantRepository assistantRepo;
    @Autowired InboxService inbox;
    @Autowired ObjectMapper json;
    @MockitoBean EvolutionApiClient evolution;
    @MockitoBean ModerationClient moderation;
    @MockitoBean TranscriptionClient transcription;
    @MockitoBean VisionClient vision;
    @MockitoBean AssistantResponder responder;

    @Test
    void productPhotosAndLogoKeepOnlyTheCloudinaryReference() throws Exception {
        UUID store = UUID.randomUUID(), product = UUID.randomUUID();
        String email = UUID.randomUUID() + "@nube.test", password = "clave-" + UUID.randomUUID();
        db.update("INSERT INTO tenant(id,name) VALUES (?, 'Tienda Nube')", store);
        db.update("INSERT INTO tenant_owner(tenant_id,email,password_hash) VALUES (?,?,?)", store, email,
                new BCryptPasswordEncoder().encode(password));
        db.update("INSERT INTO product(id,tenant_id,name,category) VALUES (?,?,'Polo nube','Polos')", product, store);
        db.update("INSERT INTO product_variant(product_id,tenant_id,sku,size,color,price,stock_physical) VALUES (?,?,'PN-M','M','Negro',39.90,2)",
                product, store);
        var owner = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        assertEquals(200, form(owner, "/api/owner/login", "username=" + enc(email) + "&password=" + enc(password) + "&store=" + store).statusCode());

        String images = "/api/owner/stores/" + store + "/catalog/products/" + product + "/images";
        var uploaded = upload(owner, images, PNG);
        assertEquals(201, uploaded.statusCode(), uploaded.body());
        assertTrue(uploaded.body().contains("\"url\":\"https://res.cloudinary.com/demo/image/upload/v1/omnicanal/" + store + "/products/"),
                uploaded.body());
        var row = db.queryForMap("SELECT id, content, storage_url, storage_public_id, storage_bytes FROM product_image WHERE tenant_id=?", store);
        assertNull(row.get("content"), "el binario no se guarda en la base");
        assertEquals(PNG.length, row.get("storage_bytes"));
        assertArrayEquals(PNG, storage.files.get((String) row.get("storage_url")));

        // El enlace antiguo por id redirige a Cloudinary; la ficha pública y el catálogo devuelven la URL.
        var photo = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/api/public/stores/" + store + "/images/" + row.get("id"))).build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(302, photo.statusCode());
        assertEquals(row.get("storage_url"), photo.headers().firstValue("Location").orElseThrow());
        var detail = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/api/public/stores/" + store + "/products/" + product)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(detail.body().contains("\"imageUrls\":[\"" + row.get("storage_url") + "\"]"), detail.body());
        var catalog = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/api/public/stores/" + store + "/catalog")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(catalog.body().contains("\"coverImageUrl\":\"" + row.get("storage_url") + "\""), catalog.body());

        // Borrar la foto la borra también de Cloudinary.
        assertEquals(200, json(owner, "DELETE", images + "/" + row.get("id"), "").statusCode());
        assertTrue(storage.deleted.contains((String) row.get("storage_public_id")));

        var logo = upload(owner, "/api/owner/stores/" + store + "/appearance/logo", PNG);
        assertEquals(200, logo.statusCode(), logo.body());
        assertTrue(logo.body().contains("\"logoUrl\":\"https://res.cloudinary.com/demo/image/upload/v1/omnicanal/" + store + "/logo/"), logo.body());
        assertTrue(logo.body().contains("\"hasLogo\":true"));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM store_appearance WHERE tenant_id=? AND logo IS NULL AND logo_storage_url IS NOT NULL",
                Integer.class, store));
        var publicLogo = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/api/public/stores/" + store + "/logo")).build(),
                HttpResponse.BodyHandlers.discarding());
        assertEquals(302, publicLogo.statusCode());
        String oldLogo = db.queryForObject("SELECT logo_storage_public_id FROM store_appearance WHERE tenant_id=?", String.class, store);
        var removed = json(owner, "DELETE", "/api/owner/stores/" + store + "/appearance/logo", "");
        assertTrue(removed.body().contains("\"hasLogo\":false"), removed.body());
        assertTrue(storage.deleted.contains(oldLogo));
    }

    @Test
    void whatsappMediaGoesToCloudinaryAndModerationStillGetsTheBytes() {
        UUID store = UUID.randomUUID();
        String instance = EvolutionApiClient.instanceName(store), chat = "51955500011@s.whatsapp.net";
        db.update("INSERT INTO tenant(id,name,whatsapp_instance_id) VALUES (?,?,?)", store, "Tienda WA Nube", instance);
        db.update("INSERT INTO tenant_owner(tenant_id,email,password_hash) VALUES (?,?, 'x')", store, store + "@wa-nube.test");
        db.update("INSERT INTO tenant_subscription(tenant_id,plan_code,status,trial_ends_at) VALUES (?, 'TRIAL', 'TRIAL', now() + interval '5 days')", store);
        assertTrue(assistantRepo.saveSettings(store, 0, true, "", 10));
        when(evolution.configured()).thenReturn(true);
        when(evolution.sendText(anyString(), anyString(), anyString())).thenReturn(SendResult.accepted("OUT-" + UUID.randomUUID()));
        when(responder.configured()).thenReturn(true);
        when(responder.answer(eq(store), any(), anyString(), anyList(), any(), anyString()))
                .thenReturn(new Answer("Claro.", false, null, null, List.of()));
        when(moderation.enabled()).thenReturn(true);
        when(evolution.media(anyString(), any())).thenReturn(new EvolutionApiClient.Downloaded(JPG, "image/jpeg", null));

        // Foto limpia: queda en Cloudinary y la bandeja devuelve su URL.
        when(moderation.check(any(), anyString())).thenReturn(new ModerationClient.Result(false, 0.01));
        upsert(store, instance, chat, "S1");
        verify(responder, timeout(5000)).answer(eq(store), any(), anyString(), anyList(), any(), anyString());
        verify(moderation).check(eq(JPG), eq("image/jpeg"));
        UUID safe = mediaId(store, "S1");
        var stored = media.read(store, safe).orElseThrow();
        assertNull(stored.content());
        assertTrue(stored.url().startsWith("https://res.cloudinary.com/demo/image/upload/v1/omnicanal/" + store + "/whatsapp/inbound/"));
        var message = inbox.conversation(store, chat).messages().get(0);
        assertEquals("/media/" + safe, message.mediaUrl());
        assertEquals(stored.url(), message.fileUrl());

        // Foto explícita (otro contenido: el mismo archivo heredaría el resultado en caché): se guarda, pero su URL
        // nunca sale en la bandeja.
        byte[] other = JPG.clone();
        other[other.length - 1] = 42;
        when(evolution.media(anyString(), any())).thenReturn(new EvolutionApiClient.Downloaded(other, "image/jpeg", null));
        when(moderation.check(any(), anyString())).thenReturn(new ModerationClient.Result(true, 0.95));
        upsert(store, instance, chat, "N1");
        verify(evolution, timeout(5000)).sendText(eq(instance), eq(chat), contains("contenido explícito"));
        UUID blocked = mediaId(store, "N1");
        assertTrue(media.blocked(store, blocked));
        var blockedMessage = inbox.conversation(store, chat).messages().stream()
                .filter(m -> ("/media/" + blocked).equals(m.mediaUrl())).findFirst().orElseThrow();
        assertNull(blockedMessage.fileUrl());
    }

    @Test
    void backfillMovesOldDatabaseFilesToCloudinary() {
        UUID store = UUID.randomUUID(), product = UUID.randomUUID();
        db.update("INSERT INTO tenant(id,name) VALUES (?, 'Tienda Antigua')", store);
        db.update("INSERT INTO product(id,tenant_id,name,category) VALUES (?,?,'Polo viejo','Polos')", product, store);
        UUID image = db.queryForObject("INSERT INTO product_image(tenant_id,product_id,position,media_type,content) VALUES (?,?,0,'image/png',?) RETURNING id",
                UUID.class, store, product, PNG);
        UUID file = media.store(store, com.saas.omnicanal.whatsapp.model.MediaKind.DOCUMENT, "application/pdf", "lista.pdf",
                "%PDF-1.4 hola".getBytes(StandardCharsets.UTF_8));
        db.update("INSERT INTO store_appearance(tenant_id,logo_media_type,logo) VALUES (?,'image/png',?)", store, PNG);

        assertTrue(backfill.runAll() >= 3);
        var img = db.queryForMap("SELECT content, storage_url FROM product_image WHERE id=?", image);
        assertNull(img.get("content"));
        assertArrayEquals(PNG, storage.files.get((String) img.get("storage_url")));
        var doc = media.read(store, file).orElseThrow();
        assertNull(doc.content());
        assertTrue(doc.url().contains("/raw/upload/"), doc.url());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM store_appearance WHERE tenant_id=? AND logo IS NULL AND logo_storage_url IS NOT NULL",
                Integer.class, store));
        // Idempotente: una segunda pasada no vuelve a subir lo de esta tienda.
        int before = storage.files.size();
        backfill.runAll();
        assertEquals(before, storage.files.size());
    }

    @Test
    void resourceTypesAndRawExtensions() {
        assertEquals("image", MediaStorage.resourceType("image/webp"));
        assertEquals("video", MediaStorage.resourceType("video/mp4"));
        assertEquals("video", MediaStorage.resourceType("audio/ogg"));
        assertEquals("raw", MediaStorage.resourceType("application/pdf"));
        assertEquals("raw", MediaStorage.resourceType("image/svg+xml"));
        assertEquals("raw", MediaStorage.resourceType(null));
        assertEquals(".pdf", CloudinaryMediaStorage.extension("application/pdf", null));
        assertEquals(".docx", CloudinaryMediaStorage.extension("application/octet-stream", "Pedido.DOCX"));
        assertEquals("", CloudinaryMediaStorage.extension("application/octet-stream", "sin-extension"));
        var real = new CloudinaryMediaStorage("demo", "k", "s", "omnicanal");
        assertTrue(real.enabled());
        assertTrue(real.ownUrl("https://res.cloudinary.com/demo/image/upload/v1/x.jpg"));
        assertFalse(real.ownUrl("https://res.cloudinary.com/otra/image/upload/v1/x.jpg"));
        assertFalse(real.ownUrl("http://169.254.169.254/latest"));
        assertFalse(new CloudinaryMediaStorage("", "", "", "omnicanal").enabled());
    }

    private UUID mediaId(UUID store, String message) {
        for (int i = 0; i < 100; i++) {
            String url = db.queryForObject("SELECT media_url FROM whatsapp_inbound_message WHERE tenant_id=? AND message_id=?", String.class, store, message);
            if (url != null) return UUID.fromString(url.substring(7));
            try { Thread.sleep(50); } catch (InterruptedException e) { throw new IllegalStateException(e); }
        }
        throw new AssertionError("El archivo no se guardó");
    }

    private void upsert(UUID store, String instance, String chat, String id) {
        String event = """
                {"event":"messages.upsert","instance":"%s","data":{"key":{"id":"%s","remoteJid":"%s","fromMe":false},"message":{"imageMessage":{"mimetype":"image/jpeg"}}}}
                """.formatted(instance, id, chat);
        webhooks.receive(store, webhooks.credential(store), json.readTree(event));
    }

    private String csrf(HttpClient browser) throws Exception {
        var m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(get(browser, "/api/owner/csrf").body());
        assertTrue(m.find());
        return m.group(1);
    }

    private HttpResponse<String> get(HttpClient browser, String path) throws Exception {
        return browser.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> form(HttpClient browser, String path, String fields) throws Exception {
        return browser.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-CSRF-TOKEN", csrf(browser)).POST(HttpRequest.BodyPublishers.ofString(fields)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> json(HttpClient browser, String method, String path, String body) throws Exception {
        return browser.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json").header("X-CSRF-TOKEN", csrf(browser))
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> upload(HttpClient browser, String path, byte[] content) throws Exception {
        String boundary = "----omni" + UUID.randomUUID();
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"foto.png\"\r\nContent-Type: image/png\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + content.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(content, 0, body, head.length, content.length);
        System.arraycopy(tail, 0, body, head.length + content.length, tail.length);
        return browser.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-CSRF-TOKEN", csrf(browser)).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
