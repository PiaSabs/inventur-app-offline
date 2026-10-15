package de.pia.inventur.backend.dto;

import static de.pia.inventur.backend.dto.RequestJsonFelder.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Genau sieben Pflichtfelder; Sitzungs-/Blatt-ID stammen ausschliesslich aus dem Pfad. */
public record ZaehlblattAbschlussHttpRequest(UUID requestId, UUID zaehlerlisteId,
        long zuweisungVersion, long erwarteteSitzungStatusVersion, long erwarteteBlattStatusVersion,
        boolean abschlussBestaetigt, boolean unvollstaendigBestaetigt) {

    public static ZaehlblattAbschlussHttpRequest lese(byte[] json) {
        try {
            var root = leseObjekt(json, 7);
            return new ZaehlblattAbschlussHttpRequest(uuid(root, "requestId"), uuid(root, "zaehlerlisteId"),
                    zahl(root, "zuweisungVersion", 0), zahl(root, "erwarteteSitzungStatusVersion", 1),
                    zahl(root, "erwarteteBlattStatusVersion", 1), bestaetigung(root, "abschlussBestaetigt"),
                    bestaetigung(root, "unvollstaendigBestaetigt"));
        } catch (RuntimeException invalid) {
            // Keine Jackson-Ursachen oder Ausschnitte aus dem Request offenlegen.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ungueltiger Abschlussrequest");
        }
    }
}
