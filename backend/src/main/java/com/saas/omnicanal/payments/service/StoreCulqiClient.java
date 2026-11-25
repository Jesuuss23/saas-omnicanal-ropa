package com.saas.omnicanal.payments.service;

import com.saas.omnicanal.shared.web.HttpClients;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Culqi con la llave secreta de cada tienda (no la del SaaS): así cada cargo se acredita en la cuenta del comercio.
 * Nunca registra la llave en los logs.
 */
@Component
public class StoreCulqiClient {
    private static final Logger log = LoggerFactory.getLogger(StoreCulqiClient.class);
    private final RestClient api;
    private final ObjectMapper json;

    public StoreCulqiClient(@Value("${culqi.api-url:https://api.culqi.com}") String url, ObjectMapper json) {
        this.api = HttpClients.standard().baseUrl(url).build();
        this.json = json;
    }

    /** Resultado de crear un cargo: {@code status} HTTP de Culqi y su respuesta (cargo, REVIEW de 3DS o error). */
    public record ChargeResult(int status, JsonNode body) {}

    /** {@code GET /v2/charges?limit=1}: 200 si la llave es válida, 401 si Culqi la rechaza. */
    public boolean validSecretKey(String secretKey) {
        try {
            api.get().uri("/v2/charges?limit=1").headers(h -> h.setBearerAuth(secretKey)).retrieve().toBodilessEntity();
            return true;
        } catch (RestClientResponseException ex) {
            int code = ex.getStatusCode().value();
            if (code == 401 || code == 403) return false;
            log.warn("Culqi respondió HTTP {} al validar la llave de una tienda", code);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Culqi no pudo validar la llave. Intenta en un momento.", ex);
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo conectar con Culqi.", ex);
        }
    }

    /** {@code POST /v2/charges}. Devuelve también las respuestas 4xx (tarjeta rechazada) para mostrar el mensaje de Culqi. */
    public ChargeResult charge(String secretKey, Map<String, Object> body) {
        try {
            var res = api.post().uri("/v2/charges").headers(h -> h.setBearerAuth(secretKey))
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toEntity(JsonNode.class);
            return new ChargeResult(res.getStatusCode().value(), res.getBody() == null ? json.createObjectNode() : res.getBody());
        } catch (RestClientResponseException ex) {
            int code = ex.getStatusCode().value();
            if (code >= 500) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Culqi no respondió. Intenta de nuevo.", ex);
            return new ChargeResult(code, parse(ex.getResponseBodyAsString()));
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo conectar con Culqi.", ex);
        }
    }

    /** {@code GET /v2/charges/{id}}: el webhook nunca confía en el cuerpo del evento, siempre vuelve a consultar el cargo. */
    public JsonNode getCharge(String secretKey, String chargeId) {
        try {
            return api.get().uri("/v2/charges/{id}", chargeId).headers(h -> h.setBearerAuth(secretKey))
                    .retrieve().body(JsonNode.class);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cargo inexistente en Culqi", ex);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Culqi no respondió", ex);
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo conectar con Culqi.", ex);
        }
    }

    private JsonNode parse(String text) {
        try {
            return text == null || text.isBlank() ? json.createObjectNode() : json.readTree(text);
        } catch (RuntimeException ex) {
            return json.createObjectNode();
        }
    }
}
