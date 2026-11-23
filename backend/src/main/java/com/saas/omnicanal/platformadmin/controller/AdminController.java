package com.saas.omnicanal.platformadmin.controller;

import java.util.Map;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/superadmin")
class AdminController {
    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken csrf) {
        return Map.of("token", csrf.getToken());
    }
}
