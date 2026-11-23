package com.saas.omnicanal.auth.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

/** Reemplaza los roles de la sesión actual (por ejemplo, al terminar de enrolar el segundo factor). */
@Component
public class SessionUpgrade {
    private final HttpSessionSecurityContextRepository repository = new HttpSessionSecurityContextRepository();

    public void grant(Authentication current, List<String> roles, HttpServletRequest request, HttpServletResponse response) {
        var upgraded = UsernamePasswordAuthenticationToken.authenticated(current.getPrincipal(), null,
                AuthorityUtils.createAuthorityList(roles));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(upgraded);
        SecurityContextHolder.setContext(context);
        request.changeSessionId(); // nuevo id de sesión al subir de privilegios
        repository.saveContext(context, request, response);
    }
}
