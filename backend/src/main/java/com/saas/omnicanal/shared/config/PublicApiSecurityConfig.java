package com.saas.omnicanal.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Cadenas de seguridad para las rutas que no usan sesión: catálogo público, checkout de invitado
 * y webhooks de proveedores externos. Cada webhook valida su propia firma o credencial en el servicio.
 */
@Configuration
public class PublicApiSecurityConfig {
    public static final String WEBHOOKS = "/api/webhooks/**";

    @Bean
    @Order(-1)
    SecurityFilterChain publicStoreApi(HttpSecurity http) throws Exception {
        String guestPath = "/api/public/stores/*/reservation/guest";
        String trackingPath = "/api/public/stores/*/orders/*";
        String culqiPay = trackingPath + "/culqi/pay";
        String paymentConfig = "/api/public/stores/*/payment-config";
        return http.securityMatcher("/api/public/catalog-files/*", "/api/public/stores/*/catalog",
                        "/api/public/stores/*/images/*", "/api/public/stores/*/logo",
                        "/api/public/stores/*/availability", "/api/public/stores/*/products/*",
                        "/api/public/stores/*/reservation", guestPath,
                        guestPath + "/quote", trackingPath, culqiPay, paymentConfig)
                .csrf(c -> c.ignoringRequestMatchers("/api/public/stores/*/reservation", guestPath,
                        guestPath + "/quote", culqiPay))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/api/public/catalog-files/*", "/api/public/stores/*/catalog",
                                "/api/public/stores/*/availability", "/api/public/stores/*/images/*",
                                "/api/public/stores/*/logo", "/api/public/stores/*/products/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/public/stores/*/reservation", guestPath,
                                guestPath + "/quote", culqiPay).permitAll()
                        .requestMatchers(HttpMethod.DELETE, "/api/public/stores/*/reservation").permitAll()
                        .requestMatchers(HttpMethod.GET, guestPath, trackingPath, paymentConfig).permitAll()
                        .requestMatchers(HttpMethod.PUT, guestPath).permitAll()
                        .anyRequest().denyAll())
                .build();
    }

    /** Sitio de venta: planes, prueba gratis y recuperación de acceso. Sin sesión, así CSRF no aplica. */
    @Bean
    @Order(-2)
    SecurityFilterChain publicOnboarding(HttpSecurity http) throws Exception {
        String[] posts = {"/api/public/signup", "/api/public/signup/preview", "/api/public/signup/confirm",
                "/api/public/password-reset", "/api/public/password-reset/confirm"};
        return http.securityMatcher("/api/public/plans", "/api/public/signup/**", "/api/public/signup",
                        "/api/public/password-reset", "/api/public/password-reset/**")
                .csrf(c -> c.disable())
                .requestCache(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(
                        org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/api/public/plans").permitAll()
                        .requestMatchers(HttpMethod.POST, posts).permitAll()
                        .anyRequest().denyAll())
                .build();
    }

    @Bean
    @Order(0)
    SecurityFilterChain webhooks(HttpSecurity http) throws Exception {
        // GET is required by Meta's webhook verification handshake (hub.challenge).
        return http.securityMatcher(WEBHOOKS)
                .csrf(c -> c.disable())
                .requestCache(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(
                        org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, WEBHOOKS).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/webhooks/whatsapp-cloud/*").permitAll()
                        .anyRequest().denyAll())
                .build();
    }
}
