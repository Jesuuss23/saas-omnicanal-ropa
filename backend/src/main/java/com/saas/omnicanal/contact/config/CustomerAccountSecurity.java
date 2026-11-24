package com.saas.omnicanal.contact.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Cuenta del comprador: sin sesión de Spring (la cookie propia la valida CustomerAccountService).
 * Sin CSRF de Spring: la cookie es SameSite=Lax y los POST exigen JSON, que un formulario de otro sitio no puede enviar.
 */
@Configuration
class CustomerAccountSecurity {
    @Bean
    @Order(-3)
    SecurityFilterChain customerAccount(HttpSecurity http) throws Exception {
        String base = "/api/public/stores/*/account";
        return http.securityMatcher(base, base + "/**")
                .csrf(c -> c.disable())
                .requestCache(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, base, base + "/config").permitAll()
                        .requestMatchers(HttpMethod.POST, base + "/google", base + "/logout").permitAll()
                        .anyRequest().denyAll())
                .build();
    }
}
