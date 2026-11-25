package com.saas.omnicanal.fiscal.service;

import com.saas.omnicanal.shared.web.HttpClients;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Nubefact (API JSON): un POST a la RUTA de la cuenta con {@code Authorization: Token token="..."} y el campo
 * {@code operacion} ({@code generar_comprobante} / {@code consultar_comprobante}). Nunca registra el token.
 */
@Component
public class NubefactInvoiceProvider implements InvoiceProvider {
    /** 10 token, 11 ruta, 12 cabecera, 50/51 cuenta suspendida. */
    private static final Set<String> CREDENTIAL_ERRORS = Set.of("10", "11", "12", "50", "51");
    /** Errores de formato o datos: el comprobante no se generó. 23 (ya existe) y 40 (interno) no entran aquí. */
    private static final Set<String> NOT_CREATED = Set.of("10", "11", "12", "20", "21", "22", "50", "51");
    private static final Map<String, String> MESSAGES = Map.of(
            "10", "Nubefact rechazó el TOKEN. Cópialo de nuevo desde tu cuenta (API - Integración).",
            "11", "La RUTA no es correcta. Cópiala de nuevo desde tu cuenta de Nubefact (API - Integración).",
            "12", "Nubefact no aceptó la autenticación enviada.",
            "50", "Tu cuenta de Nubefact está suspendida.",
            "51", "Tu cuenta de Nubefact está suspendida por falta de pago.");

    private final RestClient api = HttpClients.builder(Duration.ofSeconds(5), Duration.ofSeconds(30)).build();
    private final ObjectMapper json;

    public NubefactInvoiceProvider(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void test(Credentials credentials, String receiptSeries) {
        try {
            status(credentials, 2, receiptSeries, 99999999);
        } catch (ProviderException ex) {
            // 24: el documento no existe. Significa que la RUTA y el TOKEN fueron aceptados.
            if (!"24".equals(ex.code())) throw ex;
        }
    }

    @Override
    public JsonNode send(Credentials credentials, Map<String, Object> document) {
        var body = new LinkedHashMap<String, Object>();
        body.put("operacion", "generar_comprobante");
        body.putAll(document);
        return post(credentials, body);
    }

    @Override
    public JsonNode status(Credentials credentials, int type, String series, int number) {
        return post(credentials, Map.of("operacion", "consultar_comprobante",
                "tipo_de_comprobante", type, "serie", series, "numero", number));
    }

    private JsonNode post(Credentials credentials, Map<String, Object> body) {
        try {
            JsonNode reply = api.post().uri(credentials.apiUrl())
                    .header("Authorization", "Token token=\"" + credentials.token() + "\"")
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(JsonNode.class);
            if (reply == null) throw new ProviderException(502, null, "Nubefact no devolvió respuesta.", false, false);
            if (reply.has("errors")) throw error(200, reply);
            return reply;
        } catch (RestClientResponseException ex) {
            JsonNode reply = null;
            try { reply = json.readTree(ex.getResponseBodyAsString()); } catch (RuntimeException ignored) { }
            if (reply != null && reply.has("errors")) throw error(ex.getStatusCode().value(), reply);
            throw new ProviderException(ex.getStatusCode().value(), null,
                    "Nubefact respondió HTTP " + ex.getStatusCode().value() + ". Inténtalo de nuevo en unos minutos.",
                    ex.getStatusCode().value() == 401 || ex.getStatusCode().value() == 404, ex.getStatusCode().value() == 401);
        } catch (RestClientException ex) {
            throw new ProviderException(504, null, "No se pudo conectar con Nubefact (sin respuesta). Consulta el estado antes de reenviar.", false, false);
        }
    }

    private static ProviderException error(int http, JsonNode reply) {
        String code = reply.path("codigo").asText("");
        String detail = reply.path("errors").isArray() ? reply.path("errors").toString() : reply.path("errors").asText("");
        String message = MESSAGES.getOrDefault(code, "Nubefact: " + (detail.isBlank() ? "error " + code : detail));
        return new ProviderException(http, code.isBlank() ? null : code, message, NOT_CREATED.contains(code), CREDENTIAL_ERRORS.contains(code));
    }
}
