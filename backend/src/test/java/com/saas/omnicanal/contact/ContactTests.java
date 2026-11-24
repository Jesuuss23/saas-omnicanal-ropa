package com.saas.omnicanal.contact;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.saas.omnicanal.contact.model.GoogleIdentity;
import com.saas.omnicanal.contact.service.ContactService;
import com.saas.omnicanal.contact.service.GoogleTokenVerifier;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/** Contacto unificado: pedidos por teléfono, ingreso con Google, duplicados y fusión reversible. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContactTests {
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired ContactService contacts;
    @Autowired TransactionTemplate tx;
    @MockitoBean GoogleTokenVerifier google;
    private final UUID product = UUID.randomUUID(), variant = UUID.randomUUID();

    @Test
    void ordersGoogleAccountAndReversibleMerge() throws Exception {
        UUID store = UUID.randomUUID();
        db.update("INSERT INTO tenant(id,name) VALUES (?, 'Contactos')", store);
        db.update("INSERT INTO product(id,tenant_id,name) VALUES (?,?,'Polo')", product, store);
        db.update("INSERT INTO product_variant(id,product_id,tenant_id,sku,size,price,stock_physical) VALUES (?,?,?,?,'M',50,10)",
                variant, product, store, "CT-" + variant.toString().substring(0, 8));

        // Dos pedidos de invitado con el mismo celular escrito distinto: un solo contacto.
        UUID first = order(store, "Ana", "987654321"), second = order(store, "Ana Torres", "+51 987654321");
        UUID guest = attach(store, first, null);
        assertEquals(guest, attach(store, second, null));
        assertEquals("51987654321", db.queryForObject("SELECT phone FROM contact WHERE tenant_id=? AND id=?", String.class, store, guest));
        assertEquals("Ana Torres", db.queryForObject("SELECT full_name FROM contact WHERE tenant_id=? AND id=?", String.class, store, guest));

        // Ingreso con Google: crea la cuenta y deja la cookie solo para esta tienda.
        when(google.configured()).thenReturn(true);
        when(google.verify(anyString())).thenReturn(Optional.of(new GoogleIdentity("sub-" + store, "ana@correo.test", "Ana T.")));
        var browser = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        var signIn = browser.send(HttpRequest.newBuilder(uri("/api/public/stores/" + store + "/account/google"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"credential\":\"x.y.z\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, signIn.statusCode(), signIn.body());
        String cookie = signIn.headers().firstValue("Set-Cookie").orElse("");
        assertTrue(cookie.contains("HttpOnly") && cookie.contains("Path=/api/public/stores/" + store) && cookie.contains("SameSite=Lax"), cookie);
        UUID account = UUID.fromString(signIn.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1"));
        assertNotEquals(guest, account, "el teléfono escrito como invitado no se une solo a la cuenta");
        assertEquals(401, HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/api/public/stores/" + store + "/account")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());

        // La compradora con cuenta compra con el número que ya usó como invitada: el pedido va a su cuenta
        // y el par aparece como posible duplicado en vez de fusionarse solo.
        UUID third = order(store, "Ana", "987654321");
        assertEquals(account, attach(store, third, account));
        var duplicates = contacts.duplicates(store);
        assertEquals(1, duplicates.size());
        assertNull(db.queryForObject("SELECT phone FROM contact WHERE tenant_id=? AND id=?", String.class, store, account));

        // Fusión: la cuenta conserva sus datos, toma el teléfono y los pedidos del invitado.
        var merge = contacts.merge(store, account, guest, "propietario");
        assertEquals(2, merge.movedOrders());
        assertEquals("51987654321", db.queryForObject("SELECT phone FROM contact WHERE tenant_id=? AND id=?", String.class, store, account));
        assertEquals(3, db.queryForObject("SELECT count(*) FROM web_checkout_order WHERE tenant_id=? AND contact_id=?", Integer.class, store, account));
        var me = browser.send(HttpRequest.newBuilder(uri("/api/public/stores/" + store + "/account")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, me.statusCode());
        assertEquals(3, me.body().split("\"trackingPath\"").length - 1, me.body());
        assertTrue(contacts.duplicates(store).isEmpty());

        // Deshacer devuelve todo como estaba.
        contacts.revert(store, merge.id(), "propietario");
        assertNull(db.queryForObject("SELECT phone FROM contact WHERE tenant_id=? AND id=?", String.class, store, account));
        assertNull(db.queryForObject("SELECT merged_into FROM contact WHERE tenant_id=? AND id=?", UUID.class, store, guest));
        assertEquals(2, db.queryForObject("SELECT count(*) FROM web_checkout_order WHERE tenant_id=? AND contact_id=?", Integer.class, store, guest));
        assertThrows(Exception.class, () -> contacts.revert(store, merge.id(), "propietario"), "no se deshace dos veces");

        // Cerrar sesión invalida la cookie en el servidor.
        assertEquals(204, browser.send(HttpRequest.newBuilder(uri("/api/public/stores/" + store + "/account/logout"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(401, browser.send(HttpRequest.newBuilder(uri("/api/public/stores/" + store + "/account")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    private UUID attach(UUID store, UUID order, UUID signedIn) {
        return tx.execute(s -> contacts.attachOrder(store, order, signedIn));
    }

    private UUID order(UUID store, String name, String phone) {
        UUID reservation = UUID.randomUUID(), order = UUID.randomUUID();
        String stored = phone.replaceAll("[^0-9+]", "");
        db.update("INSERT INTO cart_reservation(tenant_id,id,guest_name,guest_phone) VALUES (?,?,?,?)", store, reservation, name, stored);
        db.update("INSERT INTO cart_reservation_line VALUES (?,?,?,1,50)", store, reservation, variant);
        db.update("""
                INSERT INTO web_checkout_order(tenant_id,id,reservation_id,guest_name,guest_phone,merchandise_total,
                    delivery_method,fulfillment_address,pickup_hours) VALUES (?,?,?,?,?,50,'PICKUP','Gamarra','9-18')
                """, store, order, reservation, name, stored);
        return order;
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
