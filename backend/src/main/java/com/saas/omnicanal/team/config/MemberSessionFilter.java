package com.saas.omnicanal.team.config;

import com.saas.omnicanal.team.model.StoreSession;
import com.saas.omnicanal.team.repository.MemberRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Si el propietario desactiva a un miembro, su sesión abierta deja de valer en la siguiente petición. */
@Component
class MemberSessionFilter extends OncePerRequestFilter {
    private final MemberRepository members;

    MemberSessionFilter(MemberRepository members) {
        this.members = members;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/owner/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getDetails() instanceof StoreSession session && session.memberId() != null
                && !members.isActive(UUID.fromString(auth.getName()), session.memberId())) {
            var httpSession = request.getSession(false);
            if (httpSession != null) httpSession.invalidate();
            SecurityContextHolder.clearContext();
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"ok\":false,\"status\":\"member_disabled\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
