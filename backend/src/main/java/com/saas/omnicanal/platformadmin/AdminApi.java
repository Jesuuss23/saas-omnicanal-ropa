package com.saas.omnicanal.platformadmin;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/superadmin")
class AdminApi {
    private final JdbcTemplate db;

    AdminApi(JdbcTemplate db) {
        this.db = db;
    }

    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken csrf) {
        return Map.of("token", csrf.getToken());
    }

    record Store(String name, String domain) {}
    record Dashboard(String email, long stores, long products, long variants, List<Store> recent) {}

    @GetMapping("/dashboard")
    Dashboard dashboard(Principal principal) {
        var recent = db.query("SELECT name, domain FROM tenant ORDER BY created_at DESC NULLS LAST, id LIMIT 5",
                (rs, row) -> new Store(rs.getString("name"), rs.getString("domain")));
        return db.queryForObject("""
                SELECT (SELECT count(*) FROM tenant) AS stores,
                       (SELECT count(*) FROM product) AS products,
                       (SELECT count(*) FROM product_variant) AS variants
                """, (rs, row) -> new Dashboard(principal.getName(), rs.getLong("stores"),
                rs.getLong("products"), rs.getLong("variants"), recent));
    }
}
