package com.saas.omnicanal.notifications;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.saas.omnicanal.notifications.model.EmailDeliveryException;
import com.saas.omnicanal.notifications.model.EmailMessage;
import com.saas.omnicanal.notifications.repository.EmailLogRepository;
import com.saas.omnicanal.notifications.service.EmailService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** EmailService contra un Resend falso: remitente por defecto, error claro si Resend rechaza. */
class EmailServiceTests {
    private final EmailLogRepository logs = mock(EmailLogRepository.class);
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private HttpServer resend;

    private EmailService service(int status, String reply) throws Exception {
        resend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        resend.createContext("/emails", ex -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("Bearer re_test_123", ex.getRequestHeaders().getFirst("Authorization"));
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        resend.start();
        return new EmailService(logs, JsonMapper.builder().build(), "re_test_123", "", "",
                "http://127.0.0.1:" + resend.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        if (resend != null) resend.stop(0);
    }

    private static EmailMessage message() {
        return new EmailMessage("dueno@correo.test", "signup_confirmation", "Confirma", "<p>Hola</p>", "Hola");
    }

    @Test
    void sendsWithResendTestSenderByDefault() throws Exception {
        var email = service(200, "{\"id\":\"abc-123\"}");
        email.send(message());
        assertTrue(lastBody.get().contains("onboarding@resend.dev"), lastBody.get());
        verify(logs).log(eq("dueno@correo.test"), eq("signup_confirmation"), any(), eq("SENT"), eq("abc-123"), isNull(), isNull());
    }

    @Test
    void rejectionThrowsSoTheFlowFails() throws Exception {
        var email = service(403, "{\"message\":\"The domain is not verified\"}");
        var ex = assertThrows(EmailDeliveryException.class, () -> email.send(message()));
        assertEquals(502, ex.getStatusCode().value());
        assertTrue(ex.getBody().getDetail().contains("not verified"), ex.getBody().getDetail());
        verify(logs).log(any(), any(), any(), eq("FAILED"), isNull(), contains("HTTP 403"), isNull());
        assertFalse(email.sendQuietly(message()), "los avisos secundarios no lanzan");
    }
}
