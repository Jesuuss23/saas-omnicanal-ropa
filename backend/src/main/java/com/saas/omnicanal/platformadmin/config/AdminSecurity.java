package com.saas.omnicanal.platformadmin.config;

import com.saas.omnicanal.platformadmin.service.AdminLogin;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class AdminSecurity {
    @Bean
    @Order(1)
    SecurityFilterChain platformSecurity(HttpSecurity http, AdminLogin login) throws Exception {
        String root = "/api/superadmin";
        return http.securityMatcher(root + "/**")
                .authorizeHttpRequests(auth -> auth.requestMatchers(root + "/csrf", root + "/login").permitAll()
                        .anyRequest().hasRole("PLATFORM_ADMIN"))
                .authenticationManager(login::authenticate)
                .requestCache(cache -> cache.disable())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((req, res, ex) -> json(res, 401, "unauthenticated"))
                        .accessDeniedHandler((req, res, ex) -> json(res, 403, "forbidden")))
                .formLogin(form -> form.loginPage(root + "/login").loginProcessingUrl(root + "/login")
                        .authenticationDetailsSource(req -> req.getParameter("code"))
                        .successHandler((req, res, auth) -> json(res, 200, "authenticated"))
                        .failureHandler((req, res, ex) -> json(res, 401, "invalid_credentials")))
                .logout(logout -> logout.logoutUrl(root + "/logout")
                        .logoutSuccessHandler((req, res, auth) -> json(res, 200, "signed_out")))
                .build();
    }

    private static void json(HttpServletResponse response, int status, String result) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"status\":\"" + result + "\"}");
    }
}
