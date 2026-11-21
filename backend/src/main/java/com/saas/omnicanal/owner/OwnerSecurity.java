package com.saas.omnicanal.owner;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class OwnerSecurity {
    @Bean
    PasswordEncoder ownerPasswords() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    @Order(2)
    SecurityFilterChain ownerChain(HttpSecurity http, JdbcTemplate db, PasswordEncoder passwords) throws Exception {
        var provider = new DaoAuthenticationProvider(name -> {
            UUID id;
            try {
                id = UUID.fromString(name);
            } catch (IllegalArgumentException ex) {
                throw new UsernameNotFoundException("Tienda desconocida");
            }
            return db.query("SELECT password_hash FROM tenant_owner WHERE tenant_id = ? AND password_hash IS NOT NULL",
                    (rs, row) -> User.withUsername(id.toString()).password(rs.getString(1)).roles("OWNER").build(), id)
                    .stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("Tienda desconocida"));
        });
        provider.setPasswordEncoder(passwords);
        return http.securityMatcher("/api/owner/**").authenticationManager(provider::authenticate)
                .authorizeHttpRequests(a -> a.requestMatchers("/api/owner/csrf", "/api/owner/activate", "/api/owner/login").permitAll()
                        .anyRequest().hasRole("OWNER"))
                .requestCache(cache -> cache.disable())
                .exceptionHandling(e -> e.authenticationEntryPoint((q,r,x) -> reply(r,401))
                        .accessDeniedHandler((q,r,x) -> reply(r,403)))
                .formLogin(f -> f.loginPage("/api/owner/login").loginProcessingUrl("/api/owner/login")
                        .successHandler((q,r,a) -> reply(r,200)).failureHandler((q,r,x) -> reply(r,401)))
                .logout(l -> l.logoutUrl("/api/owner/logout").logoutSuccessHandler((q,r,a) -> reply(r,200)))
                .build();
    }

    private static void reply(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"ok\":" + (status == 200) + "}");
    }
}
