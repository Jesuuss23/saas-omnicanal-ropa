package com.saas.omnicanal.platformadmin;

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
        return http.securityMatcher("/superadmin", "/superadmin/**")
                .authorizeHttpRequests(auth -> auth.requestMatchers("/superadmin/login").permitAll()
                        .anyRequest().hasRole("PLATFORM_ADMIN"))
                .authenticationManager(login::authenticate)
                .formLogin(form -> form.loginPage("/superadmin/login")
                        .authenticationDetailsSource(request -> request.getParameter("code"))
                        .defaultSuccessUrl("/superadmin", true))
                .logout(logout -> logout.logoutUrl("/superadmin/logout")
                        .logoutSuccessUrl("/superadmin/login"))
                .build();
    }
}
