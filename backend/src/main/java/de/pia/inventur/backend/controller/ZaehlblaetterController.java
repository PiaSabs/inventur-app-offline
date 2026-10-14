package de.pia.inventur.backend.controller;

import java.util.List;
import java.util.UUID;

import de.pia.inventur.backend.dto.ZaehlblattResponse;
import de.pia.inventur.backend.service.ZaehlblaetterService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Die Leseservices pruefen Rolle und aktuelle Listenzuweisung. */
@RestController
public class ZaehlblaetterController {

    private final ZaehlblaetterService zaehlblaetterService;

    public ZaehlblaetterController(ZaehlblaetterService zaehlblaetterService) {
        this.zaehlblaetterService = zaehlblaetterService;
    }

    @GetMapping("/api/v1/sitzungen/{sitzungId}/blaetter")
    public List<ZaehlblattResponse> blaetter(@PathVariable("sitzungId") UUID sitzungId) {
        return zaehlblaetterService.findeFuerSitzung(sitzungId);
    }
}
