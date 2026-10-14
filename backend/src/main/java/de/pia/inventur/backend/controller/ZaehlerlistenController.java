package de.pia.inventur.backend.controller;

import java.util.List;

import de.pia.inventur.backend.dto.ZaehlerlisteResponse;
import de.pia.inventur.backend.service.ZaehlerlistenService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ZaehlerlistenController {

    private final ZaehlerlistenService zaehlerlistenService;

    public ZaehlerlistenController(ZaehlerlistenService zaehlerlistenService) {
        this.zaehlerlistenService = zaehlerlistenService;
    }

    @GetMapping("/api/v1/zaehlerlisten")
    public List<ZaehlerlisteResponse> zaehlerlisten() {
        return zaehlerlistenService.findeAlle();
    }
}
