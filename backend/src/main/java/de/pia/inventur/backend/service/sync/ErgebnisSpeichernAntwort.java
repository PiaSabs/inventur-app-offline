package de.pia.inventur.backend.service.sync;

import java.time.Instant;
import java.util.UUID;

/** Dauerhaft gespeicherter Inhalt einer akzeptierten internen Antwort. */
public record ErgebnisSpeichernAntwort(UUID requestId, String entscheidung, UUID zaehlpositionId,
        long bestaetigteLokaleRevision, long ergebnisRevision, Integer zaehlmenge,
        String anmerkung, Instant geaendertAm) {
}
