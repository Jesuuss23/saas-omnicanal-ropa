package com.saas.omnicanal.marketing.controller;

import com.saas.omnicanal.marketing.model.MarketingModels.Preferences;
import com.saas.omnicanal.marketing.service.MarketingService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Baja de comunicaciones con el enlace de cada correo. Sin sesión: el token de 128 bits identifica al contacto. */
@RestController
@RequestMapping("/api/public/unsubscribe/{token}")
class UnsubscribeController {
    private final MarketingService marketing;

    UnsubscribeController(MarketingService marketing) {
        this.marketing = marketing;
    }

    @GetMapping
    ResponseEntity<Preferences> read(@PathVariable String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(marketing.preferencesByToken(token));
    }

    /** Exige JSON (un formulario de otro sitio no lo puede enviar) y nunca se ejecuta con un GET, así un antivirus que abre el enlace no da de baja. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Preferences> unsubscribe(@PathVariable String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(marketing.unsubscribe(token));
    }
}
