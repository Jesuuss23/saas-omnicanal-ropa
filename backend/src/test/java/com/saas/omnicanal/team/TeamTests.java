package com.saas.omnicanal.team;

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
import static org.junit.jupiter.api.Assertions.*;

/** Equipo: invitación, roles con permisos distintos, cuota del plan y desactivación inmediata. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TeamTests {
    private static final Pattern LINK = Pattern.compile("#m\\.([0-9a-f-]{36})\\.([A-Za-z0-9_-]{43})");
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;

    @Test
    void ownerInvitesASellerWithLimitedAccess() throws Exception {
        UUID store = UUID.randomUUID();
        String ownerEmail = UUID.randomUUID() + "@duena.test", ownerPassword = "clave-" + UUID.randomUUID();
        db.update("INSERT INTO tenant(id,name) VALUES (?, 'Equipo Uno')", store);
        db.update("INSERT INTO tenant_owner(tenant_id,email,password_hash) VALUES (?,?,?)", store, ownerEmail,
                new BCryptPasswordEncoder().encode(ownerPassword));
        db.update("INSERT INTO tenant_subscription(tenant_id,plan_code,status,trial_ends_at) VALUES (?,'TRIAL','TRIAL',now()+interval '10 days')", store);

        var owner = browser();
        assertEquals(200, login(owner, ownerEmail, ownerPassword).statusCode());
        assertTrue(get(owner, "/api/owner/me").body().contains("\"role\":\"OWNER\""));

        String sellerEmail = UUID.randomUUID() + "@vende.test";
        var invite = json(owner, "POST", "/api/owner/stores/" + store + "/team",
                "{\"email\":\"" + sellerEmail.toUpperCase() + "\",\"fullName\":\"Ana Vende\",\"role\":\"SELLER\"}");
        assertEquals(201, invite.statusCode(), invite.body());
        assertEquals(409, json(owner, "POST", "/api/owner/stores/" + store + "/team",
                "{\"email\":\"" + sellerEmail + "\",\"fullName\":\"Ana\",\"role\":\"SELLER\"}").statusCode(), "correo repetido");
        assertEquals(402, json(owner, "POST", "/api/owner/stores/" + store + "/team",
                "{\"email\":\"otro@vende.test\",\"fullName\":\"Otro\",\"role\":\"MANAGER\"}").statusCode(), "la prueba admite 2 usuarios");
        var link = LINK.matcher(invite.body());
        assertTrue(link.find(), invite.body());
        String member = link.group(1), token = link.group(2);

        var anon = browser();
        String password = "vendedora-" + UUID.randomUUID();
        assertEquals(400, json(anon, "POST", "/api/owner/members/activate",
                "{\"store\":\"" + store + "\",\"member\":\"" + member + "\",\"token\":\"" + "x".repeat(43) + "\",\"password\":\"" + password + "\"}").statusCode());
        assertEquals(200, json(anon, "POST", "/api/owner/members/activate",
                "{\"store\":\"" + store + "\",\"member\":\"" + member + "\",\"token\":\"" + token + "\",\"password\":\"" + password + "\"}").statusCode());

        var seller = browser();
        assertEquals(200, login(seller, sellerEmail, password).statusCode());
        var me = get(seller, "/api/owner/me").body();
        assertTrue(me.contains("\"role\":\"SELLER\"") && me.contains(sellerEmail), me);
        assertEquals(200, get(seller, "/api/owner/stores/" + store + "/catalog").statusCode());
        assertEquals(200, get(seller, "/api/owner/stores/" + store + "/overview").statusCode());
        assertEquals(403, get(seller, "/api/owner/stores/" + store + "/team").statusCode());
        assertEquals(403, get(seller, "/api/owner/stores/" + store + "/appearance").statusCode());
        assertEquals(403, get(seller, "/api/owner/stores/" + store + "/subscription").statusCode());
        assertEquals(403, get(seller, "/api/owner/security").statusCode());

        var promoted = json(owner, "PUT", "/api/owner/stores/" + store + "/team/" + member, "{\"role\":\"MANAGER\",\"active\":true}");
        assertEquals(200, promoted.statusCode(), promoted.body());
        var team = get(owner, "/api/owner/stores/" + store + "/team").body();
        assertTrue(team.contains("\"used\":2") && team.contains("\"limit\":2"), team);

        assertEquals(200, json(owner, "PUT", "/api/owner/stores/" + store + "/team/" + member, "{\"role\":\"SELLER\",\"active\":false}").statusCode());
        assertEquals(401, get(seller, "/api/owner/stores/" + store + "/catalog").statusCode(), "la sesión del miembro desactivado se corta");
        assertEquals(401, login(browser(), sellerEmail, password).statusCode());
    }

    private HttpClient browser() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    }

    private HttpResponse<String> login(HttpClient browser, String email, String password) throws Exception {
        String form = "username=" + URLEncoder.encode(email, StandardCharsets.UTF_8) + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        return browser.send(HttpRequest.newBuilder(uri("/api/owner/login")).header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-CSRF-TOKEN", csrf(browser)).POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private String csrf(HttpClient browser) throws Exception {
        var m = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(get(browser, "/api/owner/csrf").body());
        assertTrue(m.find());
        return m.group(1);
    }

    private HttpResponse<String> get(HttpClient browser, String path) throws Exception {
        return browser.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> json(HttpClient browser, String method, String path, String body) throws Exception {
        return browser.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json").header("X-CSRF-TOKEN", csrf(browser))
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
