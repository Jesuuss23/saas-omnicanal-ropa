package com.saas.omnicanal.marketing;

import static org.junit.jupiter.api.Assertions.*;

import com.saas.omnicanal.marketing.model.MarketingModels.Channel;
import com.saas.omnicanal.marketing.model.MarketingModels.Segment;
import com.saas.omnicanal.marketing.service.CampaignWorker;
import com.saas.omnicanal.marketing.service.MarketingService;
import com.saas.omnicanal.marketing.service.MarketingService.Draft;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Campañas: consentimiento, cuota del plan, cola sin duplicados y baja por enlace. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MarketingTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired MarketingService marketing;
    @Autowired CampaignWorker worker;

    @Test
    void campaignRespectsConsentQuotaAndUnsubscribe() throws Exception {
        UUID store = UUID.randomUUID();
        db.update("INSERT INTO tenant(id,name) VALUES (?, 'Tienda Campañas')", store);
        db.update("INSERT INTO tenant_subscription(tenant_id,plan_code,status,trial_ends_at) VALUES (?, 'TRIAL', 'TRIAL', now() + interval '5 days')", store);
        String tag = store.toString().substring(0, 8);
        UUID ana = contact(store, "Ana", tag + "-ana@correo.test", true);
        contact(store, "Beto", tag + "-beto@correo.test", true);
        contact(store, "Carla", tag + "-carla@correo.test", false);
        UUID dani = contact(store, "Dani", tag + "-dani@correo.test", true);
        UUID eli = contact(store, "Eli", tag + "-eli@correo.test", true);
        db.update("UPDATE contact SET unsubscribed_at=now() WHERE tenant_id=? AND id=?", store, dani);

        // Solo cuentan quienes aceptaron y no se dieron de baja.
        assertEquals(3, marketing.preview(store, Channel.EMAIL, Segment.all()).recipients());
        assertEquals(0, marketing.preview(store, Channel.WHATSAPP, Segment.all()).recipients());

        var draft = marketing.create(store, new Draft("Nueva colección", Channel.EMAIL, null, "Llegó la colección",
                "Hola, ya tenemos polos nuevos.", null, null), "dueno@test");
        assertEquals("DRAFT", draft.status());

        // La prueba gratis no incluye campañas.
        var denied = assertThrows(ResponseStatusException.class, () -> marketing.launch(store, draft.id(), draft.revision()));
        assertEquals(402, denied.getStatusCode().value());

        // Con el canal habilitado pero cuota de 2 al mes, 3 destinatarios no entran.
        db.update("INSERT INTO tenant_entitlement_override(tenant_id,entitlement_key,enabled,limit_value,reason) VALUES (?, 'campaigns.email', true, 2, 'prueba')", store);
        var quota = assertThrows(ResponseStatusException.class, () -> marketing.launch(store, draft.id(), draft.revision()));
        assertEquals(402, quota.getStatusCode().value());
        db.update("UPDATE tenant_entitlement_override SET limit_value=10 WHERE tenant_id=? AND entitlement_key='campaigns.email'", store);

        var launched = marketing.launch(store, draft.id(), draft.revision());
        assertEquals("SENDING", launched.status());
        assertEquals(3, launched.stats().total());
        assertThrows(ResponseStatusException.class, () -> marketing.launch(store, draft.id(), launched.revision()), "no se lanza dos veces");

        // Eli se da de baja con el enlace antes de que salga el lote: un GET no da de baja, el POST sí.
        String token = db.queryForObject("SELECT unsubscribe_token FROM contact WHERE tenant_id=? AND id=?", String.class, store, eli);
        var http = HttpClient.newHttpClient();
        var read = http.send(HttpRequest.newBuilder(uri("/api/public/unsubscribe/" + token)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, read.statusCode(), read.body());
        assertTrue(read.body().contains("\"emailOptIn\":true") && read.body().contains("•••"), read.body());
        var post = http.send(HttpRequest.newBuilder(uri("/api/public/unsubscribe/" + token)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, post.statusCode(), post.body());
        assertTrue(post.body().contains("\"emailOptIn\":false"), post.body());
        assertEquals(404, http.send(HttpRequest.newBuilder(uri("/api/public/unsubscribe/nope")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());

        // El trabajador envía a quienes siguen suscritos y marca el resto.
        while (worker.drainOnce() > 0) { /* vacía la cola */ }
        var done = marketing.overview(store).campaigns().get(0);
        assertEquals("SENT", done.status());
        assertEquals(2, done.stats().sent());
        assertEquals(1, done.stats().skipped());
        assertEquals(2, db.queryForObject("SELECT count(*) FROM email_log WHERE template='campaign' AND recipient LIKE ?", Integer.class, tag + "-%"));
        String text = db.queryForObject("SELECT body FROM email_log WHERE template='campaign' AND recipient=?", String.class, tag + "-ana@correo.test");
        String anaToken = db.queryForObject("SELECT unsubscribe_token FROM contact WHERE tenant_id=? AND id=?", String.class, store, ana);
        assertTrue(text.contains("/baja/" + anaToken), text);

        // La cuota mensual ya cuenta lo enviado.
        assertEquals(2, marketing.overview(store).email().usedThisMonth());
    }

    private UUID contact(UUID store, String name, String email, boolean optIn) {
        return db.queryForObject("INSERT INTO contact(tenant_id,full_name,email,email_opt_in,consent_at) VALUES (?,?,?,?,now()) RETURNING id",
                UUID.class, store, name, email, optIn);
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
