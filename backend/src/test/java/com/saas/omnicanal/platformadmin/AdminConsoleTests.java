package com.saas.omnicanal.platformadmin;

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

/** Consola del superadmin: alta, ficha, kill switch de tienda, ajustes de suscripción y auditoría. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminConsoleTests {
    private static final String SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP";
    private static final String EMAIL = UUID.randomUUID() + "@consola.test";
    private static final String PASSWORD = UUID.randomUUID().toString();
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry props) {
        props.add("SUPERADMIN_EMAIL", () -> EMAIL);
        props.add("SUPERADMIN_PASSWORD_HASH", () -> new BCryptPasswordEncoder().encode(PASSWORD));
        props.add("SUPERADMIN_TOTP_SECRET", () -> SECRET);
    }

    @Test
    void superadminManagesStoresAndEveryChangeIsAudited() throws Exception {
        var admin = browser();
        String login = "username=" + enc(EMAIL) + "&password=" + enc(PASSWORD) + "&code=" + Totp.codeAt(SECRET, Totp.currentStep());
        assertEquals(200, post(admin, "/api/superadmin/login", login, "superadmin").statusCode());

        UUID store = UUID.randomUUID();
        String ownerEmail = UUID.randomUUID() + "@tienda.test";
        var created = send(admin, "POST", "/api/superadmin/tenants",
                "{\"id\":\"" + store + "\",\"name\":\"Moda Consola\",\"ownerEmail\":\"" + ownerEmail + "\"}");
        assertEquals(201, created.statusCode(), created.body());

        var dashboard = get(admin, "/api/superadmin/dashboard");
        assertEquals(200, dashboard.statusCode());
        assertTrue(dashboard.body().contains("\"kpis\"") && dashboard.body().contains("\"newStores\""), dashboard.body());

        var list = get(admin, "/api/superadmin/tenants?q=" + enc(ownerEmail));
        assertTrue(list.body().contains(store.toString()), list.body());
        assertTrue(list.body().contains("\"hasNext\":false"));
        var detail = get(admin, "/api/superadmin/tenants/" + store);
        assertEquals(200, detail.statusCode());
        assertTrue(detail.body().contains("\"status\":\"TRIAL\""), detail.body());
        assertEquals(404, get(admin, "/api/superadmin/tenants/" + UUID.randomUUID()).statusCode());

        // El dueño entra a su panel antes de la suspensión.
        String ownerPassword = "clave-" + UUID.randomUUID();
        db.update("UPDATE tenant_owner SET password_hash = ? WHERE tenant_id = ?", new BCryptPasswordEncoder().encode(ownerPassword), store);
        var owner = browser();
        assertEquals(200, post(owner, "/api/owner/login", "username=" + enc(ownerEmail) + "&password=" + enc(ownerPassword)
                + "&store=" + store, "owner").statusCode());
        assertEquals(200, get(owner, "/api/owner/stores/" + store + "/catalog").statusCode());

        assertEquals(400, send(admin, "POST", "/api/superadmin/tenants/" + store + "/suspend", "{\"reason\":\"\"}").statusCode());
        var suspended = send(admin, "POST", "/api/superadmin/tenants/" + store + "/suspend", "{\"reason\":\"Fraude reportado\"}");
        assertEquals(200, suspended.statusCode(), suspended.body());
        assertEquals(409, send(admin, "POST", "/api/superadmin/tenants/" + store + "/suspend", "{\"reason\":\"otra\"}").statusCode());

        var blocked = get(owner, "/api/owner/stores/" + store + "/catalog");
        assertEquals(423, blocked.statusCode());
        assertTrue(blocked.body().contains("store_suspended"));
        assertEquals(200, get(owner, "/api/owner/stores/" + store + "/subscription").statusCode(), "puede ver su suscripción");
        assertTrue(get(owner, "/api/owner/me").body().contains("Fraude reportado"));
        assertEquals(423, get(browser(), "/api/public/stores/" + store + "/catalog").statusCode());
        assertTrue(get(admin, "/api/superadmin/tenants?status=SUSPENDED_STORE&size=100").body().contains(store.toString()));

        assertEquals(200, send(admin, "POST", "/api/superadmin/tenants/" + store + "/reactivate", "").statusCode());
        assertEquals(200, get(owner, "/api/owner/stores/" + store + "/catalog").statusCode());

        var extended = send(admin, "PUT", "/api/superadmin/tenants/" + store + "/subscription",
                "{\"action\":\"EXTEND_TRIAL\",\"days\":7,\"reason\":\"Piloto\"}");
        assertEquals(200, extended.statusCode(), extended.body());
        assertTrue(extended.body().contains("SUPERADMIN"));
        assertEquals(400, send(admin, "PUT", "/api/superadmin/tenants/" + store + "/subscription",
                "{\"action\":\"ACTIVATE\",\"reason\":\"sin plan\"}").statusCode());
        var activated = send(admin, "PUT", "/api/superadmin/tenants/" + store + "/subscription",
                "{\"action\":\"ACTIVATE\",\"planCode\":\"BASICO\",\"months\":1,\"reason\":\"Pago por transferencia\"}");
        assertEquals(200, activated.statusCode(), activated.body());
        assertTrue(activated.body().contains("\"status\":\"ACTIVE\""));

        var audit = get(admin, "/api/superadmin/audit?tenant=" + store);
        assertEquals(200, audit.statusCode());
        for (String action : new String[] {"TENANT_CREATED", "TENANT_SUSPENDED", "TENANT_REACTIVATED", "SUBSCRIPTION_CHANGED"}) {
            assertTrue(audit.body().contains(action), action + " en " + audit.body());
        }
        assertTrue(audit.body().contains(EMAIL), "registra al actor");
        assertEquals(200, get(admin, "/api/superadmin/payments?provider=CULQI").statusCode());
        assertNotEquals(200, get(owner, "/api/superadmin/tenants").statusCode(), "el dueño no entra a la consola");
    }

    private HttpClient browser() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
    }

    private String csrf(HttpClient browser, String scope) throws Exception {
        var match = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(get(browser, "/api/" + scope + "/csrf").body());
        assertTrue(match.find());
        return match.group(1);
    }

    private HttpResponse<String> get(HttpClient browser, String path) throws Exception {
        return browser.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(HttpClient browser, String path, String fields, String scope) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-CSRF-TOKEN", csrf(browser, scope)).POST(HttpRequest.BodyPublishers.ofString(fields)).build();
        return browser.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(HttpClient browser, String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", csrf(browser, "superadmin")).method(method, HttpRequest.BodyPublishers.ofString(body)).build();
        return browser.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
