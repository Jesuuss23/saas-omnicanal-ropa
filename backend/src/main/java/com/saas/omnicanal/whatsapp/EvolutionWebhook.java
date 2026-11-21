package com.saas.omnicanal.whatsapp;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@RestController
class EvolutionWebhook {
    static final String ROOT = "/api/webhooks/evolution";
    static final String HEADER = "X-Store-Webhook";
    private final String secret;
    private final JdbcTemplate db;

    EvolutionWebhook(@Value("${EVOLUTION_WEBHOOK_SECRET:}") String secret, JdbcTemplate db) {
        this.secret = secret;
        this.db = db;
    }

    @Bean
    @Order(0)
    static SecurityFilterChain webhookSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher(ROOT+"/*").csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.requestMatchers(org.springframework.http.HttpMethod.POST,ROOT+"/*")
                        .permitAll().anyRequest().denyAll()).build();
    }

    String credential(UUID store) {
        if (secret.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(store.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @PostMapping(ROOT+"/{store}")
    @org.springframework.transaction.annotation.Transactional
    Map<String,Integer> receive(@PathVariable UUID store, @RequestHeader(value=HEADER,defaultValue="") String key,
                                @RequestBody JsonNode event) {
        if (!MessageDigest.isEqual(credential(store).getBytes(StandardCharsets.UTF_8),key.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String instance = "tienda-"+store;
        if (!instance.equals(event.path("instance").asText("")) || !Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM tenant WHERE id=? AND whatsapp_instance_id=?)",Boolean.class,store,instance)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        String name = event.path("event").asText("").replace('_','.');
        boolean update = "messages.update".equalsIgnoreCase(name);
        if (!update && !"messages.upsert".equalsIgnoreCase(name)) return Map.of("inserted",0);
        JsonNode data = event.path("data");
        int count = 0;
        Iterable<JsonNode> items = data.isArray() ? data : java.util.List.of(data);
        for (JsonNode item : items) {
            count += update ? receipt(store,item) : save(store,item);
        }
        return Map.of(update ? "updated" : "inserted",count);
    }

    private int receipt(UUID store, JsonNode item) {
        JsonNode key = item.path("key");
        JsonNode outgoing = item.has("fromMe") ? item.path("fromMe") : key.path("fromMe");
        if (!outgoing.isBoolean() || !outgoing.asBoolean()) return 0;
        String id = item.path("keyId").asText(key.path("id").asText(""));
        String chat = item.path("remoteJid").asText(key.path("remoteJid").asText(""));
        chat = chat.replaceFirst(":[0-9]+@s[.]whatsapp[.]net$", "@s.whatsapp.net");
        if (id.isBlank() || id.length()>200 || !chat.matches("[0-9]{5,15}@s[.]whatsapp[.]net")) return 0;
        String rawStatus = item.path("status").asText(item.path("update").path("status").asText(""));
        String status = switch (rawStatus) {
            case "DELIVERY_ACK","3" -> "DELIVERED";
            case "READ","4" -> "READ";
            default -> null;
        };
        if (status==null) return 0;
        // The receipt can arrive before sendText returns the provider ID to the CRM.
        return db.update("""
                INSERT INTO whatsapp_reply_receipt(tenant_id,chat_id,provider_id,status)
                SELECT ?,?,?,? WHERE EXISTS (
                    SELECT 1 FROM whatsapp_manual_reply WHERE tenant_id=? AND chat_id=?
                      AND (provider_id=? OR (provider_id IS NULL AND status='REVIEW'
                           AND created_at>=now()-interval '5 minutes')))
                ON CONFLICT (tenant_id,chat_id,provider_id) DO UPDATE SET status=excluded.status
                 WHERE whatsapp_reply_receipt.status='DELIVERED' AND excluded.status='READ'
                """,store,chat,id,status,store,chat,id);
    }

    private int save(UUID store, JsonNode item) {
        JsonNode key = item.path("key");
        if (!key.path("fromMe").isBoolean() || key.path("fromMe").asBoolean()) return 0;
        String chat = key.path("remoteJid").asText("");
        if (!(chat.endsWith("@s.whatsapp.net") || chat.endsWith("@lid"))) return 0;
        String id = key.path("id").asText("");
        JsonNode message = item.path("message");
        String body = message.path("conversation").asText("");
        if (body.isBlank()) body = message.path("extendedTextMessage").path("text").asText("");
        if (body.isBlank()) return 0;
        if (id.isBlank() || id.length()>200 || chat.length()>160) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return db.update("""
                INSERT INTO whatsapp_inbound_message(tenant_id,chat_id,message_id,body) VALUES (?,?,?,?)
                ON CONFLICT (tenant_id,chat_id,message_id) DO NOTHING
                """,store,chat,id,body);
    }
}
