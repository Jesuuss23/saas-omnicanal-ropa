package com.saas.omnicanal.whatsapp.service;

import com.saas.omnicanal.shared.crypto.SecretCipher;
import com.saas.omnicanal.whatsapp.model.ChatIds;
import com.saas.omnicanal.whatsapp.model.CloudApiChannel;
import com.saas.omnicanal.whatsapp.model.CloudApiCredentialRecord;
import com.saas.omnicanal.whatsapp.model.CloudApiCredentials;
import com.saas.omnicanal.whatsapp.model.SendResult;
import com.saas.omnicanal.whatsapp.model.WhatsAppTemplate;
import com.saas.omnicanal.whatsapp.repository.CloudApiCredentialRepository;
import com.saas.omnicanal.whatsapp.repository.WhatsAppMessageRepository;
import com.saas.omnicanal.whatsapp.repository.WhatsAppTemplateRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Conexión gestionada con WhatsApp Cloud API por tienda: credenciales cifradas, verificación,
 * sincronización de plantillas de la WABA y envíos de prueba.
 */
@Service
public class CloudApiService {
    public static final String WEBHOOK_ROOT = "/api/webhooks/whatsapp-cloud";

    private final CloudApiCredentialRepository credentials;
    private final WhatsAppTemplateRepository templates;
    private final WhatsAppMessageRepository messages;
    private final CloudApiClient client;
    private final SecretCipher cipher;
    private final String publicBase;

    public CloudApiService(CloudApiCredentialRepository credentials, WhatsAppTemplateRepository templates,
                           WhatsAppMessageRepository messages, CloudApiClient client, SecretCipher cipher,
                           @Value("${PUBLIC_API_BASE_URL:${EVOLUTION_CALLBACK_BASE_URL:}}") String publicBase) {
        this.credentials = credentials;
        this.templates = templates;
        this.messages = messages;
        this.client = client;
        this.cipher = cipher;
        this.publicBase = publicBase.replaceAll("/+$", "");
    }

    static String tokenContext(UUID store) { return "whatsapp-cloud/access-token/" + store; }
    static String secretContext(UUID store) { return "whatsapp-cloud/app-secret/" + store; }

    public String verifyToken(UUID store) {
        return cipher.derive("whatsapp-cloud/verify-token/" + store);
    }

    public CloudApiChannel channel(UUID store) {
        boolean ready = cipher.configured();
        String url = publicBase.isBlank() ? null : publicBase + WEBHOOK_ROOT + "/" + store;
        String verify = ready ? verifyToken(store) : null;
        return credentials.find(store).map(r -> new CloudApiChannel(true, r.phoneNumberId(), r.wabaId(),
                        r.accessTokenCiphertext() != null, r.appSecretCiphertext() != null, r.displayPhoneNumber(),
                        r.verifiedName(), r.qualityRating(), r.status(), r.lastError(), r.verifiedAt(), r.revision(),
                        url, verify, ready))
                .orElse(new CloudApiChannel(false, null, null, false, false, null, null, null, "NOT_CONFIGURED",
                        null, null, 0, url, verify, ready));
    }

    public record CredentialsInput(String phoneNumberId, String wabaId, String accessToken, String appSecret, long revision) {}

    /** Guarda credenciales. Un token o secreto en blanco conserva el valor ya guardado. */
    @Transactional
    public CloudApiChannel save(UUID store, CredentialsInput input) {
        String phone = digits(input.phoneNumberId());
        String waba = digits(input.wabaId());
        if (phone == null || waba == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "IDs inválidos");
        if (credentials.phoneNumberTakenByOther(store, phone))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ese número ya está vinculado a otra tienda");
        String token = blankToNull(input.accessToken());
        String secret = blankToNull(input.appSecret());
        Optional<CloudApiCredentialRecord> current = credentials.findForUpdate(store);
        if (current.isEmpty()) {
            if (token == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta el token de acceso");
            credentials.insert(store, phone, waba, cipher.encrypt(tokenContext(store), token),
                    secret == null ? null : cipher.encrypt(secretContext(store), secret));
        } else {
            var row = current.get();
            byte[] tokenCipher = token == null ? row.accessTokenCiphertext() : cipher.encrypt(tokenContext(store), token);
            byte[] secretCipher = secret == null ? row.appSecretCiphertext() : cipher.encrypt(secretContext(store), secret);
            if (!credentials.update(store, input.revision(), phone, waba, tokenCipher, secretCipher))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "La configuración cambió; recarga la página");
        }
        return channel(store);
    }

    @Transactional
    public void disconnect(UUID store) {
        credentials.delete(store);
        templates.replaceAll(store, List.of());
    }

    public Optional<CloudApiCredentials> credentials(UUID store) {
        return credentials.find(store).map(r -> decrypt(store, r));
    }

    private CloudApiCredentials decrypt(UUID store, CloudApiCredentialRecord r) {
        String token = cipher.decrypt(tokenContext(store), r.accessTokenCiphertext());
        String secret = r.appSecretCiphertext() == null ? null : cipher.decrypt(secretContext(store), r.appSecretCiphertext());
        return new CloudApiCredentials(store, r.phoneNumberId(), r.wabaId(), token, secret);
    }

    private CloudApiCredentialRecord requireRecord(UUID store) {
        return credentials.find(store).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT, "Primero guarda las credenciales de Cloud API"));
    }

    /** Consulta el número en Meta: confirma que el token tiene acceso al phone_number_id. */
    public CloudApiChannel verify(UUID store) {
        var record = requireRecord(store);
        var creds = decrypt(store, record);
        try {
            var phone = client.phoneNumber(creds);
            credentials.markVerified(store, record.revision(), phone.displayPhoneNumber(), phone.verifiedName(), phone.qualityRating());
        } catch (CloudApiClient.GraphException ex) {
            credentials.markInvalid(store, record.revision(), ex.getMessage());
        }
        return channel(store);
    }

    public record SyncResult(int count, List<WhatsAppTemplate> templates) {}

    @Transactional
    public SyncResult syncTemplates(UUID store) {
        var creds = decrypt(store, requireRecord(store));
        List<CloudApiClient.Template> remote;
        try {
            remote = client.templates(creds);
        } catch (CloudApiClient.GraphException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ex.getMessage());
        }
        templates.replaceAll(store, remote.stream().map(t -> new WhatsAppTemplateRepository.Row(t.id(), t.name(),
                t.language(), t.category(), t.status(), t.componentsJson())).toList());
        var list = templates.list(store);
        return new SyncResult(list.size(), list);
    }

    public List<WhatsAppTemplate> templates(UUID store) {
        return templates.list(store);
    }

    public record TestInput(String to, String kind, String text, String templateName, String language, List<String> parameters) {}

    /** Envío de prueba desde el panel: texto libre (solo dentro de la ventana de 24 h) o plantilla aprobada. */
    public SendResult sendTest(UUID store, TestInput input) {
        String to = ChatIds.normalizePhone(input.to());
        if (to == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Número inválido; usa formato internacional, p. ej. 51987654321");
        var creds = decrypt(store, requireRecord(store));
        SendResult result;
        String kind = "TEMPLATE".equalsIgnoreCase(input.kind()) ? "TEMPLATE" : "TEXT";
        if (kind.equals("TEMPLATE")) {
            String name = blankToNull(input.templateName());
            String language = blankToNull(input.language());
            if (name == null || language == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Elige una plantilla");
            if (!templates.approvedExists(store, name, language))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "La plantilla no está aprobada o no está sincronizada");
            List<String> params = input.parameters() == null ? List.of()
                    : input.parameters().stream().map(p -> p == null ? "" : p.strip()).toList();
            if (params.stream().anyMatch(p -> p.isEmpty() || p.length() > 1024))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Completa los parámetros de la plantilla");
            result = client.sendTemplate(creds, to, name, language, params);
        } else {
            String text = blankToNull(input.text());
            if (text == null || text.length() > 4096) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escribe un mensaje");
            result = client.sendText(creds, to, text);
        }
        messages.saveTest(store, to, kind, kind.equals("TEMPLATE") ? input.templateName() : null,
                result.status(), result.providerMessageId(), result.error());
        return result;
    }

    public List<WhatsAppMessageRepository.TestMessage> recentTests(UUID store) {
        return messages.recentTests(store);
    }

    private static String digits(String value) {
        if (value == null) return null;
        String v = value.strip();
        return v.matches("[0-9]{5,32}") ? v : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
