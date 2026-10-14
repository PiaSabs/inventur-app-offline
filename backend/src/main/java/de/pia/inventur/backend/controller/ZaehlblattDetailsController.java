package de.pia.inventur.backend.controller;

import java.util.UUID;
import de.pia.inventur.backend.dto.ZaehlblattDetailResponse;
import de.pia.inventur.backend.service.ZaehlblattDetailsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Die Leseservices pruefen Rolle und die gesamte Ressourcenhierarchie. */
@RestController
public class ZaehlblattDetailsController {

    private final ZaehlblattDetailsService service;

    public ZaehlblattDetailsController(ZaehlblattDetailsService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/sitzungen/{sitzungId}/blaetter/{blattId}")
    public ZaehlblattDetailResponse blatt(@PathVariable("sitzungId") UUID sitzungId,
            @PathVariable("blattId") UUID blattId) {
        return service.findeBlatt(sitzungId, blattId);
    }
}
