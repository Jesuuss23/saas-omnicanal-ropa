package com.saas.omnicanal.marketing.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** Página pública de baja: sin sesión ni CSRF (el POST exige JSON y no hay cookie que robar). */
@Configuration
class UnsubscribeSecurity {
    @Bean
    @Order(-4)
    SecurityFilterChain unsubscribe(HttpSecurity http) throws Exception {
        String path = "/api/public/unsubscribe/*";
        return http.securityMatcher(path)
                .csrf(c -> c.disable())
                .requestCache(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, path).permitAll()
                        .requestMatchers(HttpMethod.POST, path).permitAll()
                        .anyRequest().denyAll())
                .build();
    }
}
