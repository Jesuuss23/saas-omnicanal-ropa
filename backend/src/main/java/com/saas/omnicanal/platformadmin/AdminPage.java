package com.saas.omnicanal.platformadmin;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AdminPage {
    @GetMapping(value = "/superadmin/login", produces = "text/html;charset=UTF-8")
    String login(CsrfToken csrf, @RequestParam(required = false) String error) {
        return """
                <!doctype html><html lang="es"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Acceso a plataforma</title><main><h1>Superadministración</h1>
                %s<form method="post" action="/superadmin/login">
                <p><label>Correo <input type="email" name="username" autocomplete="username" required></label></p>
                <p><label>Contraseña <input type="password" name="password" autocomplete="current-password" required></label></p>
                <p><label>Código de Authenticator <input name="code" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" autocomplete="one-time-code" required></label></p>
                %s<button>Ingresar</button></form></main></html>
                """.formatted(error == null ? "" : "<p role='alert'>Acceso denegado. Verifica los datos o intenta más tarde.</p>", token(csrf));
    }

    @GetMapping(value = "/superadmin", produces = "text/html;charset=UTF-8")
    String home(CsrfToken csrf) {
        return """
                <!doctype html><html lang="es"><meta charset="utf-8"><title>Plataforma</title>
                <main><h1>Panel de superadministración</h1>
                <form method="post" action="/superadmin/logout">%s<button>Cerrar sesión</button></form></main></html>
                """.formatted(token(csrf));
    }

    private String token(CsrfToken csrf) {
        return "<input type='hidden' name='" + csrf.getParameterName() + "' value='" + csrf.getToken() + "'>";
    }
}
