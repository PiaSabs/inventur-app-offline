package de.pia.inventur.backend.controller;

import java.util.UUID;
import de.pia.inventur.backend.dto.ErgebnisSpeichernRequest;
import de.pia.inventur.backend.dto.SecurityFehlerAntwort;
import de.pia.inventur.backend.entity.SyncOperation;
import de.pia.inventur.backend.security.InventurPrincipal;
import de.pia.inventur.backend.service.sync.ErgebnisSpeichernInhalt;
import de.pia.inventur.backend.service.sync.ErgebnisSpeichernService;
import de.pia.inventur.backend.service.sync.GepruefterUrheber;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ErgebnisController {
    private final ErgebnisSpeichernService speichern;

    public ErgebnisController(ErgebnisSpeichernService speichern) { this.speichern = speichern; }

    @PutMapping(value = "/api/v1/sitzungen/{sitzungId}/positionen/{positionId}/ergebnis", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> ergebnis(@PathVariable String sitzungId, @PathVariable String positionId,
            @AuthenticationPrincipal InventurPrincipal principal, @RequestBody byte[] json) {
        if (principal == null) throw new AuthenticationCredentialsNotFoundException("Gueltige Inventuridentitaet erforderlich");
        var request = ErgebnisSpeichernRequest.lese(json);
        var inhalt = new ErgebnisSpeichernInhalt(1, SyncOperation.ERGEBNIS_SPEICHERN,
                request.zaehlerlisteId(), pfadUuid(sitzungId), request.zaehlblattId(), pfadUuid(positionId),
                request.zuweisungVersion(), request.basisRevision(), request.lokaleRevision(),
                request.erwarteteBlattStatusVersion(), request.erwarteteSitzungStatusVersion(), request.zaehlmenge(), request.anmerkung());
        // Keine vorgelagerte Rollen-/Zuweisungspruefung: der Service erkennt erst Replay
        // und prueft neue Aenderungen vollstaendig innerhalb seiner eigenen Transaktion.
        var result = speichern.speichere(request.requestId(), new GepruefterUrheber(principal.benutzerId()), inhalt);
        if (result.entscheidung() != null) {
            var entscheidung = result.entscheidung();
            return ResponseEntity.status(entscheidung.antwortHttpStatus()).contentType(MediaType.APPLICATION_JSON)
                    .body(entscheidung.antwortJson()); // Exakter gespeicherter Text, keine erneute Serialisierung.
        }
        return switch (result.code()) {
            case "UNGUELTIGE_ZAEHLMENGE", "UNGUELTIGER_REQUEST" -> fehler(400, "UNGUELTIGER_REQUEST", "Ergebnisrequest ist ungueltig.");
            case "BENUTZER_NICHT_VORHANDEN", "BENUTZER_INAKTIV", "FALSCHE_ROLLE" -> fehler(403, "ZUGRIFF_VERWEIGERT", "Die erforderliche Berechtigung fehlt.");
            case "LISTE_NICHT_ZUGEWIESEN", "FALSCHE_ZUORDNUNG", "ZIEL_NICHT_VORHANDEN", "NICHT_BERECHTIGTER_ZUGRIFF" ->
                    fehler(404, "RESSOURCE_NICHT_GEFUNDEN", "Ressource nicht gefunden.");
            case "ZUWEISUNG_GEAENDERT", "STATUSVERSION_VERALTET", "ERGEBNISREVISION_VERALTET", "REQUEST_ID_REUSED",
                    "SITZUNG_ABGESCHLOSSEN", "BLATT_ABGESCHLOSSEN", "NOCH_IN_BEARBEITUNG" ->
                    fehler(409, result.code(), "Ergebnisrequest konnte wegen eines Konflikts nicht uebernommen werden.");
            default -> throw new IllegalStateException("Unerwartetes Speicherergebnis");
        };
    }

    private ResponseEntity<SecurityFehlerAntwort> fehler(int status, String code, String detail) {
        return ResponseEntity.status(status).body(new SecurityFehlerAntwort(status, code, detail));
    }

    private UUID pfadUuid(String wert) {
        return de.pia.inventur.backend.dto.RequestJsonFelder.pfadUuid(wert);
    }
}
