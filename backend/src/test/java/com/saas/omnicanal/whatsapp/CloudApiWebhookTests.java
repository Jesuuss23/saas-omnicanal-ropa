package com.saas.omnicanal.whatsapp;

import static org.junit.jupiter.api.Assertions.*;

import com.saas.omnicanal.shared.crypto.Hmac;
import com.saas.omnicanal.whatsapp.service.CloudApiService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CloudApiWebhookTests {
    static final String APP_SECRET = "test-app-secret-" + UUID.randomUUID();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry props) {
        props.add("SECRETS_ENCRYPTION_KEY", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired CloudApiService cloud;

    UUID store;
    String phoneNumberId;
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        store = UUID.randomUUID();
        phoneNumberId = String.valueOf(System.nanoTime());
        db.update("INSERT INTO tenant(id,name) VALUES (?,?)", store, "Tienda Cloud");
        cloud.save(store, new CloudApiService.CredentialsInput(phoneNumberId, "998877665544", "EAAtoken", APP_SECRET, 0));
    }

    @Test
    void secretsAreStoredEncryptedAndNeverExposed() {
        byte[] stored = db.queryForObject("SELECT access_token_ciphertext FROM whatsapp_cloud_credential WHERE tenant_id=?",
                byte[].class, store);
        assertFalse(new String(stored, StandardCharsets.ISO_8859_1).contains("EAAtoken"));
        var channel = cloud.channel(store);
        assertTrue(channel.accessTokenConfigured());
        assertTrue(channel.appSecretConfigured());
        assertEquals("EAAtoken", cloud.credentials(store).orElseThrow().accessToken());
    }

    @Test
    void metaVerificationHandshakeRequiresTheStoreVerifyToken() throws Exception {
        String base = "/api/webhooks/whatsapp-cloud/" + store + "?hub.mode=subscribe&hub.challenge=12345&hub.verify_token=";
        var ok = get(base + cloud.verifyToken(store));
        assertEquals(200, ok.statusCode());
        assertEquals("12345", ok.body());
        assertEquals(403, get(base + "otro").statusCode());
    }

    @Test
    void signedMessagesAreStoredOnceAndRoutedByPhoneNumberId() throws Exception {
        String body = """
                {"object":"whatsapp_business_account","entry":[{"id":"1","changes":[{"field":"messages","value":{
                  "messaging_product":"whatsapp","metadata":{"display_phone_number":"51999","phone_number_id":"%s"},
                  "messages":[{"from":"51987654321","id":"wamid.TEST1","timestamp":"1","type":"text","text":{"body":"Hola, ¿tienen talla M?"}}]
                }}]}]}""".formatted(phoneNumberId);
        String signature = "sha256=" + Hmac.sha256Hex(APP_SECRET, body.getBytes(StandardCharsets.UTF_8));

        assertEquals(401, post(body, "sha256=00").statusCode());
        assertEquals(401, post(body, null).statusCode());
        var first = post(body, signature);
        assertEquals(200, first.statusCode());
        assertTrue(first.body().contains("\"inserted\":1"));
        assertTrue(post(body, signature).body().contains("\"inserted\":0"), "reintento de Meta no duplica");

        String provider = db.queryForObject("""
                SELECT provider FROM whatsapp_inbound_message WHERE tenant_id=? AND chat_id='51987654321@s.whatsapp.net'
                """, String.class, store);
        assertEquals("CLOUD_API", provider);

        String foreign = body.replace(phoneNumberId, "123450000");
        var ignored = post(foreign, "sha256=" + Hmac.sha256Hex(APP_SECRET, foreign.getBytes(StandardCharsets.UTF_8)));
        assertTrue(ignored.body().contains("\"inserted\":0"));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String body, String signature) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/webhooks/whatsapp-cloud/" + store))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (signature != null) request.header("X-Hub-Signature-256", signature);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
