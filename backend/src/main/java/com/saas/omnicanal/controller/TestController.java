package com.saas.omnicanal.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TestController {

    @GetMapping("/test")
    public Map<String, String> probarConexion() {
        return Map.of("mensaje", "¡Conexión exitosa! El Backend y Frontend están hablando.");
    }
}