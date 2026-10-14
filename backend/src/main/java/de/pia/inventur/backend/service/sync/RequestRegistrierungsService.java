package de.pia.inventur.backend.service.sync;

import java.util.UUID;
import de.pia.inventur.backend.entity.SyncRequestStatus;
import de.pia.inventur.backend.repository.SyncRequestsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Interne atomare Registrierung; keine Mengenverarbeitung oder automatische Entscheidung. */
@Service
public class RequestRegistrierungsService {

    private final SyncRequestsRepository requests;
    private final ErgebnisRequestNormalisierer normalisierer;
    private final RequestWiederholungsService wiederholung;

    public RequestRegistrierungsService(SyncRequestsRepository requests, ErgebnisRequestNormalisierer normalisierer,
            RequestWiederholungsService wiederholung) {
        this.requests = requests;
        this.normalisierer = normalisierer;
        this.wiederholung = wiederholung;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public RequestRegistrierungsergebnis registriere(UUID requestId, GepruefterUrheber urheber,
            ErgebnisSpeichernInhalt inhalt) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Registrierung erfordert eine aufrufende schreibfaehige Transaktion");
        }
        if (requestId == null || urheber == null) {
            throw new IllegalArgumentException("Request-ID und gepruefter Urheber sind erforderlich");
        }
        var normalisiert = normalisierer.normalisiere(inhalt);
        int angelegt = requests.registriereFallsUnbekannt(requestId, urheber.benutzerId(), inhalt.operation().name(),
                inhalt.zaehlerlisteId(), inhalt.zaehlsitzungId(), inhalt.zaehlblattId(), inhalt.zaehlpositionId(),
                inhalt.zuweisungVersion(), inhalt.basisRevision(), inhalt.erwarteteBlattStatusVersion(),
                inhalt.erwarteteSitzungStatusVersion(), normalisiert.normalisierterInhalt(),
                normalisiert.inhaltFingerprint(), normalisiert.inhaltFormatVersion());
        if (angelegt == 1) {
            // Keine verwaiste EINGEGANGEN-Zeile committen: Verarbeitung/Entscheidung muss
            // spaeter in genau dieser Transaktion erfolgen, sonst wird alles zurueckgerollt.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    requests.flush();
                    String status = requests.findeRegistrierungsstatus(requestId).orElseThrow(
                            () -> new IllegalStateException("Neue Registrierung ist nicht mehr vorhanden"));
                    if (!SyncRequestStatus.AKZEPTIERT.name().equals(status)
                            && !SyncRequestStatus.ABGELEHNT.name().equals(status)) {
                        throw new IllegalStateException("Neue Registrierung hat keine endgueltige Entscheidung; Rollback erforderlich");
                    }
                }
            });
            return new RequestRegistrierungsergebnis(RequestRegistrierungsstatus.NEU_REGISTRIERT, null);
        }
        if (angelegt != 0) throw new IllegalStateException("Unerwartetes Registrierungsergebnis");

        // ON CONFLICT hat die Transaktion nicht abgebrochen. Nach einem konkurrierenden
        // Commit wird bei PostgreSQL READ COMMITTED mit einem neuen SELECT erneut gelesen.
        var pruefung = wiederholung.pruefe(requestId, urheber, inhalt);
        var status = switch (pruefung.status()) {
            case BEKANNTE_ENTSCHEIDUNG -> RequestRegistrierungsstatus.BEKANNTE_ENTSCHEIDUNG;
            case REQUEST_ID_KONFLIKT -> RequestRegistrierungsstatus.REQUEST_ID_KONFLIKT;
            case NOCH_IN_BEARBEITUNG -> RequestRegistrierungsstatus.NOCH_IN_BEARBEITUNG;
            case NICHT_BERECHTIGTER_ZUGRIFF -> RequestRegistrierungsstatus.NICHT_BERECHTIGTER_ZUGRIFF;
            case NEUER_REQUEST -> throw new IllegalStateException("Konkurrierender Request ist nicht mehr sichtbar; gesamte Transaktion wiederholen");
        };
        return new RequestRegistrierungsergebnis(status, pruefung.entscheidung());
    }
}
