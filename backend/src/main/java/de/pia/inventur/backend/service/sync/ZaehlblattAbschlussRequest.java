package de.pia.inventur.backend.service.sync;
import java.util.UUID;
import de.pia.inventur.backend.entity.SyncOperation;
/** Identitaet wird separat aus einer vertrauenswuerdigen internen Quelle uebergeben. */
public record ZaehlblattAbschlussRequest(UUID requestId, Integer formatVersion,
        UUID zaehlerlisteId, UUID zaehlsitzungId, UUID zaehlblattId,
        Long zuweisungVersion, Long erwarteteSitzungStatusVersion, Long erwarteteBlattStatusVersion,
        Boolean abschlussBestaetigt, Boolean unvollstaendigBestaetigt) implements SyncRequestInhalt {
    public SyncOperation operation() { return SyncOperation.BLATT_ABSCHLIESSEN; }
    public UUID zaehlpositionId() { return null; }
    public Long basisRevision() { return null; }
}
