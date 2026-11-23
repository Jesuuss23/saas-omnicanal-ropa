package com.saas.omnicanal.owner.config;

import com.saas.omnicanal.auth.model.MfaRequiredException;
import com.saas.omnicanal.owner.model.ChooseStoreException;
import com.saas.omnicanal.owner.model.OwnerLoginDetails;
import com.saas.omnicanal.owner.service.OwnerLogin;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

@Configuration
class OwnerSecurity {
    @Bean
    PasswordEncoder ownerPasswords() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    @Order(2)
    SecurityFilterChain ownerChain(HttpSecurity http, @Lazy OwnerLogin login, ObjectMapper json) throws Exception {
        return http.securityMatcher("/api/owner/**").authenticationManager(login::authenticate)
                .authorizeHttpRequests(a -> a.requestMatchers("/api/owner/csrf", "/api/owner/activate", "/api/owner/login").permitAll()
                        .anyRequest().hasRole("OWNER"))
                .requestCache(cache -> cache.disable())
                .exceptionHandling(e -> e.authenticationEntryPoint((q,r,x) -> reply(r,401))
                        .accessDeniedHandler((q,r,x) -> reply(r,403)))
                .formLogin(f -> f.loginPage("/api/owner/login").loginProcessingUrl("/api/owner/login")
                        .authenticationDetailsSource(q -> new OwnerLoginDetails(q.getParameter("code"), q.getParameter("store")))
                        .successHandler((q,r,a) -> write(r, json, 200, Map.of("ok", true, "store", a.getName())))
                        .failureHandler((q,r,x) -> {
                            if (x instanceof ChooseStoreException choose) {
                                write(r, json, 409, Map.of("ok", false, "status", "choose_store", "stores", choose.stores()));
                            } else {
                                write(r, json, 401, Map.of("ok", false,
                                        "status", x instanceof MfaRequiredException ? "mfa_required" : "invalid_credentials"));
                            }
                        }))
                .logout(l -> l.logoutUrl("/api/owner/logout").logoutSuccessHandler((q,r,a) -> reply(r,200)))
                .build();
    }

    private static void reply(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"ok\":" + (status == 200) + "}");
    }

    private static void write(HttpServletResponse response, ObjectMapper json, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(json.writeValueAsString(body));
    }
}
