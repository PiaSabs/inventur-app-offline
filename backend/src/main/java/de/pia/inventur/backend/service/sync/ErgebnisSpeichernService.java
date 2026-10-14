package de.pia.inventur.backend.service.sync;

import java.util.Objects;
import java.util.UUID;
import de.pia.inventur.backend.entity.SyncRequestStatus;
import de.pia.inventur.backend.repository.ErgebnisSpeicherRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Nur intern mit vertrauenswuerdig gepruefter Identitaet aufrufen, keine Authentifizierung. */
@Service
public class ErgebnisSpeichernService {
    private final ErgebnisRequestNormalisierer normalisierer;
    private final RequestWiederholungsService wiederholung;
    private final ErgebnisBerechtigungsPruefer pruefer;
    private final RequestRegistrierungsService registrierung;
    private final ErgebnisSpeicherRepository daten;
    private final KonfliktErfassungService konflikte;
    private final TransactionTemplate transaktion;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public ErgebnisSpeichernService(ErgebnisRequestNormalisierer normalisierer, RequestWiederholungsService wiederholung,
            ErgebnisBerechtigungsPruefer pruefer, RequestRegistrierungsService registrierung,
            ErgebnisSpeicherRepository daten, KonfliktErfassungService konflikte, PlatformTransactionManager transactionManager) {
        this.normalisierer = normalisierer;
        this.wiederholung = wiederholung;
        this.pruefer = pruefer;
        this.registrierung = registrierung;
        this.daten = daten;
        this.konflikte = konflikte;
        transaktion = new TransactionTemplate(transactionManager);
        transaktion.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    // Keine fremde Transaktion: execute() muss vor Rueckgabe tatsaechlich committen.
    // Alle MANDATORY-Unterservices arbeiten innerhalb dieser EINEN Transaktion.
    @Transactional(propagation = Propagation.NEVER)
    public ErgebnisSpeicherergebnis speichere(UUID requestId, GepruefterUrheber urheber, ErgebnisSpeichernInhalt inhalt) {
        Objects.requireNonNull(requestId, "Request-ID erforderlich");
        Objects.requireNonNull(urheber, "Gepruefter Urheber erforderlich");
        Objects.requireNonNull(inhalt, "Requestinhalt erforderlich");
        if (inhalt.zaehlmenge() != null && inhalt.zaehlmenge() < 0) return abgelehnt("UNGUELTIGE_ZAEHLMENGE");
        try { normalisierer.normalisiere(inhalt); }
        catch (IllegalArgumentException invalid) { return abgelehnt("UNGUELTIGER_REQUEST"); }
        return Objects.requireNonNull(transaktion.execute(tx -> verarbeite(requestId, urheber, inhalt)));
    }

    private ErgebnisSpeicherergebnis verarbeite(UUID id, GepruefterUrheber urheber, ErgebnisSpeichernInhalt inhalt) {
        var bekannt = wiederholung.pruefe(id, urheber, inhalt);
        if (bekannt.status() != RequestPruefstatus.NEUER_REQUEST) return ausPruefung(bekannt);

        // Benutzer -> Liste -> Sitzung -> Blatt -> Position -> Ergebnis -> neue Request-ID.
        // Besonders die Positionssperre MUSS vor den FK-Sperren der Registrierung liegen.
        var erlaubt = pruefer.pruefe(urheber, inhalt);
        // Nach Wartezeiten erneut lesen: ein identischer Konkurrent koennte inzwischen
        // gespeichert haben. Sein Replay geht auch einer nun veralteten Basisrevision vor.
        bekannt = wiederholung.pruefe(id, urheber, inhalt);
        if (bekannt.status() != RequestPruefstatus.NEUER_REQUEST) return ausPruefung(bekannt);
        boolean konflikt = !erlaubt.zulaessig() && KonfliktErfassungService.konfliktcode(erlaubt.code()).isPresent();
        if (!erlaubt.zulaessig() && !konflikt) return abgelehnt(erlaubt.code().name());
        if (konflikt) {
            var kontext = pruefer.pruefeKonfliktKontext(urheber, inhalt);
            bekannt = wiederholung.pruefe(id, urheber, inhalt);
            if (bekannt.status() != RequestPruefstatus.NEUER_REQUEST) return ausPruefung(bekannt);
            if (!kontext.zulaessig()) return abgelehnt(kontext.code().name());
        }

        var registriert = registrierung.registriere(id, urheber, inhalt);
        if (registriert.status() != RequestRegistrierungsstatus.NEU_REGISTRIERT) {
            return switch (registriert.status()) {
                case BEKANNTE_ENTSCHEIDUNG -> replay(registriert.entscheidung());
                case REQUEST_ID_KONFLIKT -> abgelehnt("REQUEST_ID_REUSED");
                case NICHT_BERECHTIGTER_ZUGRIFF -> abgelehnt("NICHT_BERECHTIGTER_ZUGRIFF");
                case NOCH_IN_BEARBEITUNG -> abgelehnt("NOCH_IN_BEARBEITUNG");
                default -> throw new IllegalStateException("Unerwartete Registrierung");
            };
        }
        if (konflikt) return new ErgebnisSpeicherergebnis(erlaubt.code().name(),
                konflikte.erfasse(id, urheber, inhalt, erlaubt.code()), false);
        var antwort = new ErgebnisSpeichernAntwort(id, "AKZEPTIERT", inhalt.zaehlpositionId(),
                inhalt.lokaleRevision(), Math.addExact(inhalt.basisRevision(), 1),
                inhalt.zaehlmenge(), inhalt.anmerkung(), daten.serverzeit());
        String json = mapper.writeValueAsString(antwort);
        daten.speichereAktuelles(antwort, urheber.benutzerId(), inhalt.basisRevision());
        daten.ergaenzeHistorie(antwort, urheber.benutzerId());
        daten.akzeptiereRequest(antwort, json);
        return new ErgebnisSpeicherergebnis("AKZEPTIERT", new GespeicherteRequestEntscheidung(
                SyncRequestStatus.AKZEPTIERT, "AKZEPTIERT", 200, json, antwort.geaendertAm()), false);
    }

    private ErgebnisSpeicherergebnis ausPruefung(RequestPruefergebnis pruefung) {
        return switch (pruefung.status()) {
            case BEKANNTE_ENTSCHEIDUNG -> replay(pruefung.entscheidung());
            case REQUEST_ID_KONFLIKT -> abgelehnt("REQUEST_ID_REUSED");
            case NICHT_BERECHTIGTER_ZUGRIFF -> abgelehnt("NICHT_BERECHTIGTER_ZUGRIFF");
            case NOCH_IN_BEARBEITUNG -> abgelehnt("NOCH_IN_BEARBEITUNG");
            default -> throw new IllegalStateException("Unerwartete Wiederholungspruefung");
        };
    }

    private ErgebnisSpeicherergebnis replay(GespeicherteRequestEntscheidung entscheidung) {
        return new ErgebnisSpeicherergebnis(entscheidung.entscheidungscode(), entscheidung, true);
    }

    private ErgebnisSpeicherergebnis abgelehnt(String code) {
        // Nur nicht archivierte Ablehnungen: ungueltige/fremde Ziele reservieren keine ID.
        return new ErgebnisSpeicherergebnis(code, null, false);
    }
}
