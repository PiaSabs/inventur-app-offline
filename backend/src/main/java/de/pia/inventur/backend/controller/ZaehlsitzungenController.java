package de.pia.inventur.backend.controller;

import java.util.List;
import java.util.UUID;

import de.pia.inventur.backend.dto.ZaehlsitzungResponse;
import de.pia.inventur.backend.service.ZaehlsitzungenService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Die Leseservices pruefen Rolle und aktuelle Listenzuweisung. */
@RestController
public class ZaehlsitzungenController {

    private final ZaehlsitzungenService zaehlsitzungenService;

    public ZaehlsitzungenController(ZaehlsitzungenService zaehlsitzungenService) {
        this.zaehlsitzungenService = zaehlsitzungenService;
    }

    @GetMapping("/api/v1/zaehlerlisten/{listeId}/sitzungen")
    public List<ZaehlsitzungResponse> sitzungen(@PathVariable("listeId") UUID listeId) {
        return zaehlsitzungenService.findeFuerListe(listeId);
    }
}
