package de.pia.inventur.backend.dto;

import java.util.UUID;
import static de.pia.inventur.backend.dto.RequestJsonFelder.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Vollstaendiger PUT-Inhalt: alle zehn Felder erforderlich, Menge und Notiz duerfen NULL sein. */
public record ErgebnisSpeichernRequest(UUID requestId, UUID zaehlerlisteId, UUID zaehlblattId,
        long zuweisungVersion, long basisRevision, long lokaleRevision,
        long erwarteteBlattStatusVersion, long erwarteteSitzungStatusVersion, Integer zaehlmenge, String anmerkung) {
    public static ErgebnisSpeichernRequest lese(byte[] json) {
        try {
            var root = leseObjekt(json, 10);
            var menge = feld(root, "zaehlmenge");
            Integer quantity = null;
            if (!menge.isNull()) {
                if (!menge.isIntegralNumber() || !menge.canConvertToInt() || menge.intValue() < 0) throw ungueltig();
                quantity = menge.intValue();
            }
            var notiz = feld(root, "anmerkung");
            if (!notiz.isNull() && (!notiz.isString() || notiz.asString().indexOf('\0') >= 0)) throw ungueltig();
            return new ErgebnisSpeichernRequest(uuid(root, "requestId"), uuid(root, "zaehlerlisteId"), uuid(root, "zaehlblattId"),
                    zahl(root, "zuweisungVersion", 0), zahl(root, "basisRevision", 0), zahl(root, "lokaleRevision", 0),
                    zahl(root, "erwarteteBlattStatusVersion", 1), zahl(root, "erwarteteSitzungStatusVersion", 1),
                    quantity, notiz.isNull() ? null : notiz.asString());
        } catch (RuntimeException invalid) {
            // Weder Jackson-Ursachen noch Ausschnitte aus dem Request weitergeben.
            throw ungueltig();
        }
    }

    private static ResponseStatusException ungueltig() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ungueltiger Ergebnisrequest");
    }
}
