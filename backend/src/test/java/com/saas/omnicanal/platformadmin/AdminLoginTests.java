package com.saas.omnicanal.platformadmin;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminLoginTests {
    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static final String EMAIL = UUID.randomUUID() + "@example.test";
    private static final String PASSWORD = UUID.randomUUID().toString();
    @LocalServerPort int port;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry props) {
        props.add("SUPERADMIN_EMAIL", () -> EMAIL);
        props.add("SUPERADMIN_PASSWORD_HASH", () -> new BCryptPasswordEncoder().encode(PASSWORD));
        props.add("SUPERADMIN_TOTP_SECRET", () -> SECRET);
    }

    @Test
    void loginRequiresBothFactorsAndRejectsReplayAfterLogout() throws Exception {
        assertEquals("287082", AdminLogin.codeAt(SECRET, 1));
        var browser = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        assertEquals(302, get(browser, "/superadmin").statusCode());
        String csrf = csrf(get(browser, "/superadmin/login"));
        String form = "username=" + EMAIL + "&password=" + PASSWORD + "&_csrf=" + csrf;
        assertEquals(403, post(browser, "/superadmin/login", "username=x").statusCode());
        assertTrue(post(browser, "/superadmin/login", form).headers().firstValue("location").orElseThrow().endsWith("?error"));
        String code = AdminLogin.codeAt(SECRET, Instant.now().getEpochSecond() / 30);
        var login = post(browser, "/superadmin/login", form + "&code=" + code);
        assertTrue(login.headers().firstValue("location").orElseThrow().endsWith("/superadmin"));
        var home = get(browser, "/superadmin");
        assertEquals(200, home.statusCode());
        post(browser, "/superadmin/logout", "_csrf=" + csrf(home));
        assertEquals(302, get(browser, "/superadmin").statusCode());
        csrf = csrf(get(browser, "/superadmin/login"));
        var replay = post(browser, "/superadmin/login", "username=" + EMAIL + "&password=" + PASSWORD + "&_csrf=" + csrf + "&code=" + code);
        assertTrue(replay.headers().firstValue("location").orElseThrow().endsWith("?error"));
    }

    private String csrf(HttpResponse<String> page) {
        var input = Pattern.compile("name='_csrf' value='([^']+)'").matcher(page.body());
        assertTrue(input.find());
        return URLEncoder.encode(input.group(1), StandardCharsets.UTF_8);
    }

    private HttpResponse<String> get(HttpClient browser, String path) throws Exception {
        return browser.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(HttpClient browser, String path, String fields) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(fields)).build();
        return browser.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
