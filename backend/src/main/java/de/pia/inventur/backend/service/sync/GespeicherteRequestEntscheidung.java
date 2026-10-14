package de.pia.inventur.backend.service.sync;

import java.time.Instant;
import de.pia.inventur.backend.entity.SyncRequestStatus;

/** Kopie einer bereits gespeicherten Entscheidung, keine Bestaetigung einer neuen Speicherung. */
public record GespeicherteRequestEntscheidung(SyncRequestStatus requestStatus,
        String entscheidungscode, int antwortHttpStatus, String antwortJson, Instant verarbeitetAm) {
    public GespeicherteRequestEntscheidung {
        if ((requestStatus != SyncRequestStatus.AKZEPTIERT && requestStatus != SyncRequestStatus.ABGELEHNT)
                || entscheidungscode == null || entscheidungscode.isBlank()
                || antwortHttpStatus < 200 || antwortHttpStatus > 599
                || antwortJson == null || antwortJson.isBlank() || verarbeitetAm == null) {
            throw new IllegalArgumentException("Eine vollstaendige gespeicherte Entscheidung ist erforderlich");
        }
    }
}
