package com.saas.omnicanal.auth;

import com.saas.omnicanal.auth.service.Totp;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** Enrolamiento con QR y login en dos pasos, para superadmin (sin secreto en variables) y propietarios. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TwoFactorFlowTests {
    private static final String ADMIN = UUID.randomUUID() + "@admin.test";
    private static final String ADMIN_PASSWORD = UUID.randomUUID().toString();
    private static final Pattern SECRET = Pattern.compile("\"secret\":\"([A-Z2-7]+)\"");
    private static final Pattern CODES = Pattern.compile("\"([A-Z0-9]{4}-[A-Z0-9]{4})\"");
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry props) {
        props.add("SUPERADMIN_EMAIL", () -> ADMIN);
        props.add("SUPERADMIN_PASSWORD_HASH", () -> new BCryptPasswordEncoder().encode(ADMIN_PASSWORD));
        props.add("SUPERADMIN_TOTP_SECRET", () -> "");
    }

    @Test
    void superadminWithoutSecretEnrollsWithQrBeforeUsingThePanel() throws Exception {
        var browser = browser();
        String form = "username=" + enc(ADMIN) + "&password=" + enc(ADMIN_PASSWORD);
        assertEquals(200, post(browser, "/api/superadmin/login", form, "superadmin").statusCode());
        assertEquals(403, get(browser, "/api/superadmin/dashboard").statusCode(), "sin 2FA solo puede enrolar");
        assertTrue(get(browser, "/api/superadmin/me").body().contains("\"setupRequired\":true"));

        var setup = json(browser, "/api/superadmin/mfa/setup", "{}", "superadmin");
        assertEquals(200, setup.statusCode());
        assertTrue(setup.body().contains("otpauth://totp/"));
        assertTrue(setup.body().contains("data:image/png;base64,"));
        String secret = find(SECRET, setup.body());
        assertEquals(400, json(browser, "/api/superadmin/mfa/confirm", "{\"code\":\"000000\"}", "superadmin").statusCode());
        var confirm = json(browser, "/api/superadmin/mfa/confirm",
                "{\"code\":\"" + Totp.codeAt(secret, Totp.currentStep()) + "\"}", "superadmin");
        assertEquals(200, confirm.statusCode());
        String recovery = find(CODES, confirm.body());
        assertEquals(200, get(browser, "/api/superadmin/dashboard").statusCode(), "la sesión sube a acceso completo");

        // Nueva sesión: ahora la contraseña sola ya no basta.
        var next = browser();
        var step1 = post(next, "/api/superadmin/login", form, "superadmin");
        assertEquals(401, step1.statusCode());
        assertTrue(step1.body().contains("mfa_required"));
        assertEquals(200, post(next, "/api/superadmin/login", form + "&code=" + recovery, "superadmin").statusCode());
        assertEquals(200, get(next, "/api/superadmin/dashboard").statusCode());
        var reuse = browser();
        assertEquals(401, post(reuse, "/api/superadmin/login", form + "&code=" + recovery, "superadmin").statusCode(),
                "un código de recuperación sirve una sola vez");
    }

    @Test
    void ownerLogsInByEmailAndTwoFactorProtectsTheStore() throws Exception {
        UUID store = UUID.randomUUID(), other = UUID.randomUUID();
        String email = UUID.randomUUID() + "@tienda.test", password = "clave-segura-" + UUID.randomUUID();
        String hash = new BCryptPasswordEncoder(12).encode(password);
        db.update("INSERT INTO tenant(id,name) VALUES (?, 'Moda Uno'), (?, 'Moda Dos')", store, other);
        db.update("INSERT INTO tenant_owner(tenant_id,email,password_hash) VALUES (?,?,?), (?,?,?)",
                store, email, hash, other, email.toUpperCase(), hash);

        var browser = browser();
        String form = "username=" + enc(email) + "&password=" + enc(password);
        var choose = post(browser, "/api/owner/login", form, "owner");
        assertEquals(409, choose.statusCode(), "el mismo correo tiene dos tiendas");
        assertTrue(choose.body().contains("Moda Uno") && choose.body().contains("Moda Dos"));
        var login = post(browser, "/api/owner/login", form + "&store=" + store, "owner");
        assertEquals(200, login.statusCode());
        assertTrue(get(browser, "/api/owner/me").body().contains(store.toString()));

        String secret = find(SECRET, json(browser, "/api/owner/security/mfa/setup", "{}", "owner").body());
        assertEquals(200, json(browser, "/api/owner/security/mfa/confirm",
                "{\"code\":\"" + Totp.codeAt(secret, Totp.currentStep()) + "\"}", "owner").statusCode());
        assertTrue(get(browser, "/api/owner/security").body().contains("\"enabled\":true"));

        var next = browser();
        String chosen = form + "&store=" + store;
        var needsCode = post(next, "/api/owner/login", chosen, "owner");
        assertEquals(401, needsCode.statusCode());
        assertTrue(needsCode.body().contains("mfa_required"));
        assertEquals(401, post(next, "/api/owner/login", chosen + "&code=123456", "owner").statusCode());
        // El paso ya usado al confirmar no se acepta otra vez: se toma el siguiente o un código distinto.
        long step = Totp.currentStep();
        String fresh = Totp.codeAt(secret, step + 1);
        assertEquals(200, post(next, "/api/owner/login", chosen + "&code=" + fresh, "owner").statusCode());
        assertEquals(401, post(browser(), "/api/owner/login", chosen + "&code=" + fresh, "owner").statusCode(),
                "un código ya usado no sirve de nuevo");

        // La otra tienda del mismo correo no tiene 2FA y entra solo con contraseña.
        assertEquals(200, post(browser(), "/api/owner/login", form + "&store=" + other, "owner").statusCode());
    }

    private HttpClient browser() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    }

    private String csrf(HttpClient browser, String scope) throws Exception {
        return find(Pattern.compile("\"token\":\"([^\"]+)\""), get(browser, "/api/" + scope + "/csrf").body());
    }

    private HttpResponse<String> get(HttpClient browser, String path) throws Exception {
        return browser.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(HttpClient browser, String path, String fields, String scope) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-CSRF-TOKEN", csrf(browser, scope)).POST(HttpRequest.BodyPublishers.ofString(fields)).build();
        return browser.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> json(HttpClient browser, String path, String body, String scope) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", csrf(browser, scope)).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return browser.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String find(Pattern pattern, String body) {
        var match = pattern.matcher(body);
        assertTrue(match.find(), body);
        return match.group(1);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
