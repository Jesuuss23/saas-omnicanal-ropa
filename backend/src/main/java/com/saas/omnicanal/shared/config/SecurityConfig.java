package com.saas.omnicanal.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(Customizer.withDefaults()) // Aplica las reglas de CorsConfig.java
            .csrf(AbstractHttpConfigurer::disable) // Desactiva CSRF porque usarán JWT en el futuro
            .authorizeHttpRequests(auth -> auth
                // Sin esto, un 401/404 de un endpoint público se convertía en 403 al despachar /error.
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                .requestMatchers("/error").permitAll()
                .requestMatchers("/api/test").permitAll() // Hace pública la ruta de prueba
                .anyRequest().authenticated() // Protege el resto del sistema
            );
        return http.build();
    }
}