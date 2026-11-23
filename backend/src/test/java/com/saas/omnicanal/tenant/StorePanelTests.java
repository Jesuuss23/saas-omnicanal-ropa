package com.saas.omnicanal.tenant;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;

/** Panel de tienda: resumen, apariencia con logo y fotos de producto. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StorePanelTests {
    // PNG de 1x1 píxel.
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;

    @Test
    void ownerSetsUpAppearanceAndProductPhotos() throws Exception {
        UUID store = UUID.randomUUID(), other = UUID.randomUUID(), product = UUID.randomUUID();
        String email = UUID.randomUUID() + "@panel.test", password = "clave-" + UUID.randomUUID();
        db.update("INSERT INTO tenant(id,name) VALUES (?, 'Panel Uno'), (?, 'Panel Dos')", store, other);
        db.update("INSERT INTO tenant_owner(tenant_id,email,password_hash) VALUES (?,?,?)", store, email,
                new BCryptPasswordEncoder().encode(password));
        db.update("INSERT INTO product(id,tenant_id,name,category) VALUES (?,?,'Polo básico','Polos')", product, store);
        db.update("INSERT INTO product_variant(product_id,tenant_id,sku,size,color,price,stock_physical) VALUES (?,?,'PB-M','M','Negro',39.90,2)",
                product, store);

        var owner = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        assertEquals(200, form(owner, "/api/owner/login", "username=" + enc(email) + "&password=" + enc(password) + "&store=" + store).statusCode());

        var overview = get(owner, "/api/owner/stores/" + store + "/overview");
        assertEquals(200, overview.statusCode(), overview.body());
        assertTrue(overview.body().contains("\"products\":true"), overview.body());
        assertTrue(overview.body().contains("\"photos\":false"));
        assertTrue(overview.body().contains("\"lowStock\":1"));
        assertEquals(403, get(owner, "/api/owner/stores/" + other + "/overview").statusCode());

        var appearance = get(owner, "/api/owner/stores/" + store + "/appearance");
        assertTrue(appearance.body().contains("\"primaryColor\":\"#0f766e\""), appearance.body());
        assertEquals(400, json(owner, "PUT", "/api/owner/stores/" + store + "/appearance",
                "{\"name\":\"Panel Uno\",\"primaryColor\":\"rojo\",\"revision\":0}").statusCode());
        var saved = json(owner, "PUT", "/api/owner/stores/" + store + "/appearance",
                "{\"name\":\"Panel Renovado\",\"tagline\":\"Ropa urbana\",\"primaryColor\":\"#1D4ED8\",\"whatsappPhone\":\"+51 987 654 321\",\"instagram\":\"@panel.uno\",\"revision\":0}");
        assertEquals(200, saved.statusCode(), saved.body());
        assertTrue(saved.body().contains("\"whatsappPhone\":\"51987654321\"") && saved.body().contains("\"instagram\":\"panel.uno\""), saved.body());
        assertTrue(saved.body().contains("\"name\":\"Panel Renovado\""));
        assertEquals(409, json(owner, "PUT", "/api/owner/stores/" + store + "/appearance",
                "{\"name\":\"X\",\"primaryColor\":\"#000000\",\"revision\":1}").statusCode(), "revisión vieja");

        assertEquals(400, upload(owner, "/api/owner/stores/" + store + "/appearance/logo", "no es imagen".getBytes()).statusCode());
        assertEquals(200, upload(owner, "/api/owner/stores/" + store + "/appearance/logo", PNG).statusCode());
        var logo = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/api/public/stores/" + store + "/logo")).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, logo.statusCode());
        assertEquals("image/png", logo.headers().firstValue("Content-Type").orElse(""));

        String images = "/api/owner/stores/" + store + "/catalog/products/" + product + "/images";
        var first = upload(owner, images, PNG);
        assertEquals(201, first.statusCode(), first.body());
        var second = upload(owner, images, PNG);
        var ids = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"").matcher(second.body()).results().map(m -> m.group(1)).toList();
        assertEquals(2, ids.size(), second.body());
        var reordered = json(owner, "PUT", images + "/order", "{\"ids\":[\"" + ids.get(1) + "\",\"" + ids.get(0) + "\"]}");
        assertEquals(200, reordered.statusCode(), reordered.body());
        assertTrue(reordered.body().indexOf(ids.get(1)) < reordered.body().indexOf(ids.get(0)), "la segunda pasa a portada");
        assertEquals(409, json(owner, "PUT", images + "/order", "{\"ids\":[\"" + ids.get(1) + "\"]}").statusCode());
        assertEquals(404, upload(owner, "/api/owner/stores/" + store + "/catalog/products/" + UUID.randomUUID() + "/images", PNG).statusCode());

        var photo = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/api/public/stores/" + store + "/images/" + ids.get(0))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, photo.statusCode());
        assertTrue(photo.headers().firstValue("Cache-Control").orElse("").contains("immutable"));
        assertEquals(404, HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/api/public/stores/" + other + "/images/" + ids.get(0))).build(),
                HttpResponse.BodyHandlers.ofByteArray()).statusCode(), "la foto no se sirve con otra tienda");

        var catalog = get(owner, "/api/owner/stores/" + store + "/catalog");
        assertTrue(catalog.body().contains("\"coverImage\":\"" + ids.get(1) + "\""), catalog.body());
        var storefront = get(HttpClient.newHttpClient(), "/api/public/stores/" + store + "/catalog");
        assertEquals(200, storefront.statusCode(), storefront.body());
        assertTrue(storefront.body().contains("\"tagline\":\"Ropa urbana\"") && storefront.body().contains("\"logoVersion\":1")
                && storefront.body().contains("\"productId\":\"" + product + "\""), storefront.body());
        assertTrue(storefront.body().contains("\"coverImage\":\"" + ids.get(1) + "\""), "la tienda pública muestra la portada");
        var deleted = json(owner, "DELETE", images + "/" + ids.get(1), "");
        assertEquals(200, deleted.statusCode());
        assertTrue(deleted.body().contains("\"position\":0") && !deleted.body().contains(ids.get(1)));
        assertTrue(get(owner, "/api/owner/stores/" + store + "/overview").body().contains("\"photos\":true"));
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
