package de.pia.inventur.backend.service.sync;

import java.util.UUID;
import de.pia.inventur.backend.entity.SyncOperation;

/** Internes Inhaltsmodell; enthaelt weder Request-ID noch eine Client-Benutzeridentitaet. */
public record ErgebnisSpeichernInhalt(Integer formatVersion, SyncOperation operation,
        UUID zaehlerlisteId, UUID zaehlsitzungId, UUID zaehlblattId, UUID zaehlpositionId,
        Long zuweisungVersion, Long basisRevision, Long lokaleRevision,
        Long erwarteteBlattStatusVersion, Long erwarteteSitzungStatusVersion,
        Integer zaehlmenge, String anmerkung) implements SyncRequestInhalt {
}
