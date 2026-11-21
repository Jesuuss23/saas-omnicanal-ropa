package com.saas.omnicanal.whatsapp;

import java.net.http.HttpClient;
import java.security.Principal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/owner/stores/{store}/whatsapp")
class OwnerWhatsAppApi {
    private final JdbcTemplate db;
    private final RestClient api;

    private final EvolutionWebhook webhook;
    private final String callbackBase;

    OwnerWhatsAppApi(JdbcTemplate db, @Value("${EVOLUTION_API_URL:}") String url,
                     @Value("${EVOLUTION_API_KEY:}") String key, EvolutionWebhook webhook,
                     @Value("${EVOLUTION_CALLBACK_BASE_URL:}") String callbackBase) {
        this.webhook = webhook;
        this.callbackBase = callbackBase.replaceAll("/+$","");
        this.db = db;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(15));
        api = url.isBlank() || key.isBlank() ? null : RestClient.builder().baseUrl(url)
                .requestFactory(factory).defaultHeader("apikey", key).build();
    }

    record Connection(String state, String qr) {}

    @GetMapping
    ResponseEntity<Connection> status(@PathVariable UUID store, Principal owner) {
        String name = instanceName(store, owner);
        try {
            return response(new Connection(state(name), null));
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        }
    }

    @PostMapping("/connect")
    ResponseEntity<Connection> connect(@PathVariable UUID store, Principal owner) {
        String name = instanceName(store, owner);
        int bound = db.update("""
                UPDATE tenant SET whatsapp_instance_id=? WHERE id=?
                AND (whatsapp_instance_id IS NULL OR whatsapp_instance_id=?)
                """, name, store, name);
        if (bound != 1) throw new ResponseStatusException(HttpStatus.CONFLICT);
        try {
            String current = state(name);
            if ("CONNECTED".equals(current)) return response(new Connection(current, null));
            if ("UNLINKED".equals(current)) {
                try {
                    JsonNode created = api.post().uri("/instance/create").contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of("instanceName", name, "integration", "WHATSAPP-BAILEYS", "qrcode", true))
                            .retrieve().body(JsonNode.class);
                    String qr = qrImage(created == null ? null : created.path("qrcode"));
                    if (qr != null) return response(new Connection("PAIRING",qr));
                } catch (RestClientResponseException conflict) {
                    // Another request may have created our deterministic instance.
                    if ("UNLINKED".equals(state(name))) throw conflict;
                }
            }
            JsonNode result = api.get().uri("/instance/connect/{name}",name).retrieve().body(JsonNode.class);
            String qr = qrImage(result);
            return response(new Connection(qr == null ? state(name) : "PAIRING",qr));
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        }
    }

    @PostMapping("/inbox/enable")
    Map<String,Boolean> enable(@PathVariable UUID store, Principal owner) {
        String name = instanceName(store,owner);
        if (callbackBase.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        if (!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM tenant WHERE id=? AND whatsapp_instance_id=?)",
                Boolean.class,store,name))) throw new ResponseStatusException(HttpStatus.CONFLICT);
        var settings = Map.of("enabled",true, "url",callbackBase + EvolutionWebhook.ROOT + "/" + store,
                "headers",Map.of(EvolutionWebhook.HEADER,webhook.credential(store)),
                "byEvents",false,"base64",false,"events",java.util.List.of("MESSAGES_UPSERT"));
        try {
            api.post().uri("/webhook/set/{name}",name).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("webhook",settings)).retrieve().toBodilessEntity();
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
        }
        return Map.of("enabled",true);
    }

    record Incoming(String chat, String id, String text, java.time.OffsetDateTime receivedAt) {}

    @GetMapping("/inbox")
    ResponseEntity<java.util.List<Incoming>> inbox(@PathVariable UUID store, Principal owner) {
        if (owner == null || !store.toString().equals(owner.getName())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        var rows = db.query("""
                SELECT chat_id,message_id,body,received_at FROM whatsapp_inbound_message
                 WHERE tenant_id=? ORDER BY received_at DESC,chat_id,message_id LIMIT 20
                """,(rs,row)->new Incoming(rs.getString(1),rs.getString(2),rs.getString(3),rs.getObject(4,java.time.OffsetDateTime.class)),store);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rows);
    }

    private String instanceName(UUID store, Principal owner) {
        if (owner == null || !store.toString().equals(owner.getName()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (api == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return "tienda-" + store;
    }

    private String state(String name) {
        try {
            JsonNode result = api.get().uri("/instance/connectionState/{name}",name).retrieve().body(JsonNode.class);
            String state = result == null ? "" : result.path("instance").path("state").asText("");
            return switch (state) {
                case "open" -> "CONNECTED";
                case "connecting" -> "PAIRING";
                case "close", "closed" -> "DISCONNECTED";
                default -> throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
            };
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value()==404) return "UNLINKED";
            throw ex;
        }
    }

    private static String qrImage(JsonNode qr) {
        if (qr == null) return null;
        String image = qr.path("base64").asText("");
        String prefix = "data:image/png;base64,";
        String data = image.startsWith(prefix) ? image.substring(prefix.length()) : image;
        return data.startsWith("iVBORw0KGgo") && data.length() < 512000 ? prefix + data : null;
    }

    private static ResponseEntity<Connection> response(Connection result) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
}
