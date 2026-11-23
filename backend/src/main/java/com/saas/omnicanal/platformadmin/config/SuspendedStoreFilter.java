package com.saas.omnicanal.platformadmin.config;

import com.saas.omnicanal.platformadmin.repository.AdminConsoleRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Kill switch de tienda: si el superadmin suspendió la tienda, su panel y su tienda pública responden 423.
 * El dueño todavía puede iniciar sesión y consultar su suscripción para ver el motivo.
 */
@Component
class SuspendedStoreFilter extends OncePerRequestFilter {
    private static final Pattern STORE_PATH =
            Pattern.compile("^/api/(owner|public)/stores/([0-9a-fA-F-]{36})(/.*)?$");

    private final AdminConsoleRepository console;

    SuspendedStoreFilter(AdminConsoleRepository console) {
        this.console = console;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Matcher match = STORE_PATH.matcher(request.getRequestURI().substring(request.getContextPath().length()));
        if (match.matches() && !allowedWhileSuspended(match, request) && suspended(match.group(2))) {
            response.setStatus(423);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"error\":\"store_suspended\",\"message\":\"La tienda está suspendida. Escríbenos para reactivarla.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean allowedWhileSuspended(Matcher match, HttpServletRequest request) {
        return "owner".equals(match.group(1)) && "/subscription".equals(match.group(3)) && "GET".equals(request.getMethod());
    }

    private boolean suspended(String id) {
        try {
            return console.isSuspended(UUID.fromString(id));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
