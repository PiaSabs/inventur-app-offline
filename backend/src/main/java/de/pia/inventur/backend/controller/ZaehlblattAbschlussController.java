package de.pia.inventur.backend.controller;

import java.nio.charset.StandardCharsets;
import de.pia.inventur.backend.dto.RequestJsonFelder;
import de.pia.inventur.backend.dto.SecurityFehlerAntwort;
import de.pia.inventur.backend.dto.ZaehlblattAbschlussHttpRequest;
import de.pia.inventur.backend.security.InventurPrincipal;
import de.pia.inventur.backend.service.sync.GepruefterUrheber;
import de.pia.inventur.backend.service.sync.ZaehlblattAbschlussRequest;
import de.pia.inventur.backend.service.sync.ZaehlblattAbschlussService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ZaehlblattAbschlussController {
    private final ZaehlblattAbschlussService abschluss;
    public ZaehlblattAbschlussController(ZaehlblattAbschlussService abschluss) { this.abschluss = abschluss; }

    @PutMapping(value = "/api/v1/sitzungen/{sitzungId}/blaetter/{blattId}/abschluss",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> abschliessen(@PathVariable String sitzungId, @PathVariable String blattId,
            @AuthenticationPrincipal InventurPrincipal principal, @RequestBody byte[] json) {
        if (principal == null) throw new AuthenticationCredentialsNotFoundException("Gueltige Inventuridentitaet erforderlich");
        var r = ZaehlblattAbschlussHttpRequest.lese(json);
        var request = new ZaehlblattAbschlussRequest(r.requestId(), 1, r.zaehlerlisteId(),
                RequestJsonFelder.pfadUuid(sitzungId), RequestJsonFelder.pfadUuid(blattId),
                r.zuweisungVersion(), r.erwarteteSitzungStatusVersion(), r.erwarteteBlattStatusVersion(),
                r.abschlussBestaetigt(), r.unvollstaendigBestaetigt());
        // Keine vorgelagerte fachliche Rollen-/Zuweisungspruefung: sicheres Replay hat Vorrang.
        var result = abschluss.abschliessen(new GepruefterUrheber(principal.benutzerId()), request);
        if (result.entscheidung() != null) {
            var entscheidung = result.entscheidung();
            // ByteArrayHttpMessageConverter gibt exakt den gespeicherten UTF-8-Text aus,
            // ohne erneute JSON-Serialisierung. Header sind keine gespeicherte Replay-Antwort.
            return ResponseEntity.status(entscheidung.antwortHttpStatus()).contentType(MediaType.APPLICATION_JSON)
                    .body(entscheidung.antwortJson().getBytes(StandardCharsets.UTF_8));
        }
        return switch (result.code()) {
            case "UNGUELTIGER_REQUEST" -> fehler(400, "UNGUELTIGER_REQUEST", "Abschlussrequest ist ungueltig.");
            case "BENUTZER_NICHT_VORHANDEN", "BENUTZER_INAKTIV", "FALSCHE_ROLLE" ->
                    fehler(403, "ZUGRIFF_VERWEIGERT", "Die erforderliche Berechtigung fehlt.");
            case "LISTE_NICHT_ZUGEWIESEN", "FALSCHE_ZUORDNUNG", "ZIEL_NICHT_VORHANDEN", "NICHT_BERECHTIGTER_ZUGRIFF" ->
                    fehler(404, "RESSOURCE_NICHT_GEFUNDEN", "Ressource nicht gefunden.");
            case "ZUWEISUNG_GEAENDERT", "STATUSVERSION_VERALTET", "REQUEST_ID_REUSED", "SITZUNG_ABGESCHLOSSEN",
                    "BLATT_ABGESCHLOSSEN", "NOCH_IN_BEARBEITUNG", "ABSCHLUSS_NICHT_BESTAETIGT",
                    "UNVOLLSTAENDIG_NICHT_BESTAETIGT" ->
                    fehler(409, result.code(), "Abschlussrequest konnte wegen eines Konflikts nicht uebernommen werden.");
            default -> throw new IllegalStateException("Unerwartetes Abschlussergebnis");
        };
    }

    private ResponseEntity<SecurityFehlerAntwort> fehler(int status, String code, String detail) {
        return ResponseEntity.status(status).body(new SecurityFehlerAntwort(status, code, detail));
    }
}
