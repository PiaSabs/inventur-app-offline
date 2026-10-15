package de.pia.inventur.backend.service.sync;
import java.util.UUID;
import de.pia.inventur.backend.entity.SyncOperation;
/** Gemeinsamer persistierter Kontext beider internen Operationen. */
public sealed interface SyncRequestInhalt permits ErgebnisSpeichernInhalt, ZaehlblattAbschlussRequest {
    Integer formatVersion();
    SyncOperation operation();
    UUID zaehlerlisteId();
    UUID zaehlsitzungId();
    UUID zaehlblattId();
    UUID zaehlpositionId();
    Long zuweisungVersion();
    Long basisRevision();
    Long erwarteteBlattStatusVersion();
    Long erwarteteSitzungStatusVersion();
}
