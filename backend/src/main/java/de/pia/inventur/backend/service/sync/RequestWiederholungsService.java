package de.pia.inventur.backend.service.sync;

import java.util.Objects;
import java.util.UUID;
import de.pia.inventur.backend.entity.SyncRequestEntity;
import de.pia.inventur.backend.entity.SyncRequestStatus;
import de.pia.inventur.backend.repository.SyncRequestsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Interne Wiederholungspruefung fuer ERGEBNIS_SPEICHERN; keine Reservierung oder fachliche Aenderung. */
@Service
public class RequestWiederholungsService {

    private final SyncRequestsRepository requests;
    private final ErgebnisRequestNormalisierer normalisierer;

    public RequestWiederholungsService(SyncRequestsRepository requests, ErgebnisRequestNormalisierer normalisierer) {
        this.requests = requests;
        this.normalisierer = normalisierer;
    }

    // Bewusst nicht readOnly: PostgreSQL erlaubt SELECT FOR SHARE nicht in READ ONLY-Transaktionen.
    // Die DB-Sperre bleibt bis zum Ende dieser bzw. der aufrufenden Transaktion bestehen.
    @Transactional
    public RequestPruefergebnis pruefe(UUID requestId, GepruefterUrheber urheber, ErgebnisSpeichernInhalt inhalt) {
        if (requestId == null || urheber == null) {
            throw new IllegalArgumentException("Request-ID und gepruefter Urheber sind erforderlich");
        }
        var normalisiert = normalisierer.normalisiere(inhalt);
        var gefunden = requests.findeZurWiederholungspruefung(requestId);
        if (gefunden.isEmpty()) return ohneAntwort(RequestPruefstatus.NEUER_REQUEST);

        var request = gefunden.orElseThrow();
        // Urheber zuerst: keine fremde Antwort oder Inhaltsauskunft herausgeben.
        if (!request.getUrheber().getId().equals(urheber.benutzerId())) {
            return ohneAntwort(RequestPruefstatus.NICHT_BERECHTIGTER_ZUGRIFF);
        }
        if (request.getInhaltFormatVersion() != normalisiert.inhaltFormatVersion()
                || !Objects.equals(request.getInhaltFingerprint(), normalisiert.inhaltFingerprint())
                || !Objects.equals(request.getNormalisierterInhalt(), normalisiert.normalisierterInhalt())
                || !gleicherKontext(request, inhalt)) {
            return ohneAntwort(RequestPruefstatus.REQUEST_ID_KONFLIKT);
        }
        if (request.getStatus() == SyncRequestStatus.EINGEGANGEN) {
            return ohneAntwort(RequestPruefstatus.NOCH_IN_BEARBEITUNG);
        }
        return new RequestPruefergebnis(RequestPruefstatus.BEKANNTE_ENTSCHEIDUNG,
                new GespeicherteRequestEntscheidung(request.getStatus(), request.getEntscheidungscode(),
                        request.getAntwortHttpStatus(), request.getAntwortJson(), request.getVerarbeitetAm()));
    }

    private static boolean gleicherKontext(SyncRequestEntity request, ErgebnisSpeichernInhalt inhalt) {
        return request.getOperation() == inhalt.operation()
                && request.getZaehlerliste().getId().equals(inhalt.zaehlerlisteId())
                && request.getZaehlsitzung() != null && request.getZaehlsitzung().getId().equals(inhalt.zaehlsitzungId())
                && request.getZaehlblatt() != null && request.getZaehlblatt().getId().equals(inhalt.zaehlblattId())
                && request.getZaehlposition() != null && request.getZaehlposition().getId().equals(inhalt.zaehlpositionId())
                && request.getZuweisungVersion() == inhalt.zuweisungVersion()
                && Objects.equals(request.getErwarteteErgebnisrevision(), inhalt.basisRevision())
                && Objects.equals(request.getErwarteteBlattStatusVersion(), inhalt.erwarteteBlattStatusVersion())
                && Objects.equals(request.getErwarteteSitzungStatusVersion(), inhalt.erwarteteSitzungStatusVersion());
    }

    private static RequestPruefergebnis ohneAntwort(RequestPruefstatus status) {
        return new RequestPruefergebnis(status, null);
    }
}
