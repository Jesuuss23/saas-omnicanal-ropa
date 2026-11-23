package com.saas.omnicanal.onboarding;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Prueba gratis con enlace mágico y recuperación de contraseña, de punta a punta por HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OnboardingFlowTests {
    private static final Pattern TOKEN = Pattern.compile("#([A-Za-z0-9_-]{43})");
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void trialSignupCreatesTheStoreOnlyAfterConfirmingTheEmailLink() throws Exception {
        String email = "Nueva-" + UUID.randomUUID() + "@Tienda.test";
        String body = """
                {"storeName":"Boutique Lima","ownerName":"Ana Pérez","email":"%s","phone":"987 654 321","plan":"PRO","acceptTerms":true}
                """.formatted(email);
        assertEquals(202, post("/api/public/signup", body).statusCode());
        assertEquals(0, count("SELECT count(*) FROM tenant_owner WHERE lower(email) = lower(?)", email), "aún sin confirmar");

        String token = lastToken(email, "signup_confirmation");
        var preview = post("/api/public/signup/preview", "{\"token\":\"" + token + "\"}");
        assertEquals(200, preview.statusCode());
        assertTrue(preview.body().contains("Boutique Lima"));
        assertEquals(400, post("/api/public/signup/confirm", "{\"token\":\"" + token + "\",\"password\":\"corta\"}").statusCode());
        var created = post("/api/public/signup/confirm", "{\"token\":\"" + token + "\",\"password\":\"una-clave-larga-123\"}");
        assertEquals(200, created.statusCode());
        assertEquals(410, post("/api/public/signup/confirm", "{\"token\":\"" + token + "\",\"password\":\"una-clave-larga-123\"}").statusCode(),
                "el enlace sirve una sola vez");
        UUID store = UUID.fromString(created.body().replaceAll(".*\"store\":\"([^\"]+)\".*", "$1"));
        assertEquals("TRIAL", db.queryForObject("SELECT status FROM tenant_subscription WHERE tenant_id = ?", String.class, store));
        assertEquals("987654321", db.queryForObject("SELECT phone FROM tenant_owner WHERE tenant_id = ?", String.class, store));
        assertEquals(1, count("SELECT count(*) FROM email_log WHERE lower(recipient) = lower(?) AND template = 'welcome'", email));

        var browser = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        assertEquals(200, ownerLogin(browser, email, "una-clave-larga-123").statusCode(), "entra con su correo");
    }

    @Test
    void botsAndRepeatedRequestsAreFiltered() throws Exception {
        String email = UUID.randomUUID() + "@bot.test";
        String bot = """
                {"storeName":"X","ownerName":"Y","email":"%s","acceptTerms":true,"website":"http://spam"}""".formatted(email);
        assertEquals(202, post("/api/public/signup", bot).statusCode());
        assertEquals(0, count("SELECT count(*) FROM signup_request WHERE email = ?", email), "el campo trampa descarta la solicitud");
        String human = bot.replace(",\"website\":\"http://spam\"", "");
        assertEquals(400, post("/api/public/signup", human.replace("true", "false")).statusCode(), "debe aceptar los términos");
        for (int i = 0; i < 3; i++) assertEquals(202, post("/api/public/signup", human).statusCode());
        assertEquals(429, post("/api/public/signup", human).statusCode());
    }

    @Test
    void ownerResetsTheirPasswordWithTheEmailLink() throws Exception {
        UUID store = UUID.randomUUID();
        String email = UUID.randomUUID() + "@reset.test";
        db.update("INSERT INTO tenant(id, name) VALUES (?, 'Tienda Reset')", store);
        db.update("INSERT INTO tenant_owner(tenant_id, email, password_hash) VALUES (?, ?, 'x')", store, email);
        assertEquals(202, post("/api/public/password-reset", "{\"email\":\"nadie-" + UUID.randomUUID() + "@x.test\"}").statusCode(),
                "no revela si el correo existe");
        assertEquals(202, post("/api/public/password-reset", "{\"email\":\"" + email.toUpperCase() + "\"}").statusCode());
        String token = lastToken(email, "password_reset");
        String confirm = "{\"store\":\"" + store + "\",\"token\":\"" + token + "\",\"password\":\"nueva-clave-segura-1\"}";
        assertEquals(200, post("/api/public/password-reset/confirm", confirm).statusCode());
        assertEquals(410, post("/api/public/password-reset/confirm", confirm).statusCode());
        var browser = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        assertEquals(200, ownerLogin(browser, email, "nueva-clave-segura-1").statusCode());
    }

    @Test
    void publicPlansListOnlyPurchasablePlansWithTheirEntitlements() throws Exception {
        var plans = http.send(HttpRequest.newBuilder(uri("/api/public/plans")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, plans.statusCode());
        assertTrue(plans.body().contains("\"BASICO\"") && plans.body().contains("catalog.products"));
        assertFalse(plans.body().contains("\"PILOTO\""));
    }

    private HttpResponse<String> ownerLogin(HttpClient browser, String email, String password) throws Exception {
        var csrf = browser.send(HttpRequest.newBuilder(uri("/api/owner/csrf")).build(), HttpResponse.BodyHandlers.ofString()).body()
                .replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
        var form = "username=" + URLEncoder.encode(email, StandardCharsets.UTF_8) + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        return browser.send(HttpRequest.newBuilder(uri("/api/owner/login")).header("X-CSRF-TOKEN", csrf)
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String lastToken(String email, String template) {
        String body = db.queryForObject("""
                SELECT body FROM email_log WHERE lower(recipient) = lower(?) AND template = ? ORDER BY created_at DESC LIMIT 1
                """, String.class, email, template);
        var match = TOKEN.matcher(body);
        assertTrue(match.find(), body);
        return match.group(1);
    }

    private int count(String sql, Object... args) {
        Integer value = db.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
