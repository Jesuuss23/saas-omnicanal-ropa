package com.saas.omnicanal.assistant.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.google.genai.errors.ApiException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import com.saas.omnicanal.catalog.repository.PublicCatalogRepository;

/** Verificación del modelo de Gemini al arrancar y descripción de errores de la API. */
class GeminiModelCheckTests {
    private HttpServer google;

    @AfterEach
    void stop() {
        if (google != null) google.stop(0);
    }

    @SuppressWarnings("unchecked")
    private GeminiResponder responder(String model) throws Exception {
        google = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        google.createContext("/v1beta/models", ex -> {
            assertEquals("clave-prueba", ex.getRequestHeaders().getFirst("x-goog-api-key"));
            String path = ex.getRequestURI().getPath();
            int status = path.endsWith("/gemini-flash-latest") || path.endsWith("/models") ? 200 : 404;
            String body = path.endsWith("/models") ? "{\"models\":[{\"name\":\"models/gemini-flash-latest\"},{\"name\":\"models/gemini-2.5-flash\"}]}"
                    : status == 200 ? "{\"name\":\"models/gemini-flash-latest\"}" : "{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\"}}";
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        google.start();
        return new GeminiResponder(mock(ObjectProvider.class), mock(PublicCatalogRepository.class), "clave-prueba", model,
                "http://127.0.0.1:" + google.getAddress().getPort() + "/v1beta", "gemini-flash-latest", 0);
    }

    @Test
    void currentAliasIsAccepted() throws Exception {
        assertTrue(responder("gemini-flash-latest").checkModel());
    }

    @Test
    void retiredModelIsReported() throws Exception {
        var r = responder("gemini-1.5-flash");
        assertEquals("gemini-1.5-flash", r.model());
        assertFalse(r.checkModel());
    }

    @Test
    void overloadIsRetriedButBadRequestsAreNot() {
        assertTrue(GeminiResponder.transientFailure(new RuntimeException(new ApiException(503, "UNAVAILABLE", "high demand"))));
        assertTrue(GeminiResponder.transientFailure(new ApiException(429, "RESOURCE_EXHAUSTED", "quota")));
        assertFalse(GeminiResponder.transientFailure(new ApiException(404, "NOT_FOUND", "no model")));
        assertFalse(GeminiResponder.transientFailure(new IllegalStateException("otro")));
    }

    @Test
    void describesApiErrorsWithStatusAndMessage() {
        var wrapped = new RuntimeException("reintentos agotados", new ApiException(404, "NOT_FOUND", "models/gemini-1.5-flash is not found"));
        assertEquals("Gemini HTTP 404 NOT_FOUND: models/gemini-1.5-flash is not found", GeminiResponder.describe(wrapped));
    }
}
