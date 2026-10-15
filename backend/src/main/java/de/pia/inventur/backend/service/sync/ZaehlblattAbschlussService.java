package de.pia.inventur.backend.service.sync;
import java.util.Objects;
import de.pia.inventur.backend.entity.*;
import de.pia.inventur.backend.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
/** Interner ausdruecklicher Blattabschluss, keine Authentifizierung. */
@Service
public class ZaehlblattAbschlussService {
    private final ErgebnisRequestNormalisierer normalisierer;
    private final RequestWiederholungsService wiederholung;
    private final RequestRegistrierungsService registrierung;
    private final ErgebnisPruefRepository pruefung;
    private final ErgebnisBerechtigungsPruefer berechtigung;
    private final ZaehlblattAbschlussRepository daten;
    private final ErgebnisSpeicherRepository server;
    private final TransactionTemplate transaktion;
    private final JsonMapper mapper = JsonMapper.builder().build();
    public ZaehlblattAbschlussService(ErgebnisRequestNormalisierer normalisierer,
            RequestWiederholungsService wiederholung, RequestRegistrierungsService registrierung,
            ErgebnisPruefRepository pruefung, ErgebnisBerechtigungsPruefer berechtigung, ZaehlblattAbschlussRepository daten,
            ErgebnisSpeicherRepository server, PlatformTransactionManager manager) {
        this.normalisierer = normalisierer; this.wiederholung = wiederholung;
        this.registrierung = registrierung; this.pruefung = pruefung; this.berechtigung = berechtigung; this.daten = daten; this.server = server;
        transaktion = new TransactionTemplate(manager);
        transaktion.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    // execute() muss vor der Rueckgabe tatsaechlich committen.
    @Transactional(propagation = Propagation.NEVER)
    public ErgebnisSpeicherergebnis abschliessen(GepruefterUrheber urheber, ZaehlblattAbschlussRequest r) {
        Objects.requireNonNull(urheber); Objects.requireNonNull(r);
        try { normalisierer.normalisiere(r); }
        catch (IllegalArgumentException invalid) { return abgelehnt("UNGUELTIGER_REQUEST"); }
        return Objects.requireNonNull(transaktion.execute(tx -> verarbeite(urheber, r)));
    }
    private ErgebnisSpeicherergebnis verarbeite(GepruefterUrheber u, ZaehlblattAbschlussRequest r) {
        var bekannt = wiederholung.pruefe(r.requestId(), u, r);
        if (bekannt.status() != RequestPruefstatus.NEUER_REQUEST) return ausPruefung(bekannt);
        String fehler = pruefe(u, r);
        // Nach Sperrwartezeiten geht eine inzwischen gespeicherte Entscheidung vor.
        bekannt = wiederholung.pruefe(r.requestId(), u, r);
        if (bekannt.status() != RequestPruefstatus.NEUER_REQUEST) return ausPruefung(bekannt);
        if (fehler != null) return abgelehnt(fehler);
        if (!r.abschlussBestaetigt()) return abgelehnt("ABSCHLUSS_NICHT_BESTAETIGT");
        long ungezaehlt = daten.ungezaehltePositionen(r.zaehlblattId());
        if (ungezaehlt > 0 && !r.unvollstaendigBestaetigt()) return abgelehnt("UNVOLLSTAENDIG_NICHT_BESTAETIGT");
        var registriert = registrierung.registriere(r.requestId(), u, r);
        if (registriert.status() != RequestRegistrierungsstatus.NEU_REGISTRIERT)
            return ausPruefung(wiederholung.pruefe(r.requestId(), u, r));
        var antwort = new ZaehlblattAbschlussAntwort(r.requestId(), r.zaehlblattId(), "AKZEPTIERT",
                ZaehlblattStatus.ABGESCHLOSSEN, Math.addExact(r.erwarteteBlattStatusVersion(), 1),
                ungezaehlt, server.serverzeit());
        daten.schliesse(antwort, u.benutzerId(), r.erwarteteBlattStatusVersion());
        // Dieselbe Sitzungssperre umfasst Blattwechsel, Vollstaendigkeitspruefung,
        // Sitzungswechsel und Requestentscheidung bis zum erfolgreichen Commit.
        boolean sitzungGeschlossen = daten.schliesseSitzungFallsVollstaendig(
                r.zaehlsitzungId(), r.erwarteteSitzungStatusVersion(), antwort.abgeschlossenAm());
        antwort = antwort.mitSitzungsstand(sitzungGeschlossen, sitzungGeschlossen
                ? Math.addExact(r.erwarteteSitzungStatusVersion(), 1) : r.erwarteteSitzungStatusVersion());
        String json = mapper.writeValueAsString(antwort);
        daten.akzeptiere(antwort, json);
        return new ErgebnisSpeicherergebnis("AKZEPTIERT", new GespeicherteRequestEntscheidung(
                SyncRequestStatus.AKZEPTIERT, "AKZEPTIERT", 200, json, antwort.abgeschlossenAm()), false);
    }
    private String pruefe(GepruefterUrheber u, ZaehlblattAbschlussRequest r) {
        // Benutzer SHARE -> Liste SHARE -> Sitzung UPDATE -> Blatt UPDATE -> neue Request-ID.
        // Kein SHARE-Upgrade. Sitzung exklusiv serialisiert auch verschiedene Blattabschluesse.
        // ErgebnisSpeichern haelt Sitzung/Blatt SHARE bis Commit; die Auswertung bleibt stabil.
        // Neuzuweisungen muessen ebenfalls Benutzer vor Liste und vor Kindressourcen sperren.
        var zugriff = berechtigung.pruefeBenutzerUndListe(u, r, true);
        if (!zugriff.zulaessig()) return zugriff.code().name();
        var s = pruefung.sperreSitzungZumAbschluss(r.zaehlsitzungId());
        if (s.isEmpty()) return "ZIEL_NICHT_VORHANDEN";
        if (!s.orElseThrow().listeId().equals(r.zaehlerlisteId())) return "FALSCHE_ZUORDNUNG";
        var bl = pruefung.sperreBlattZumAbschluss(r.zaehlblattId());
        if (bl.isEmpty()) return "ZIEL_NICHT_VORHANDEN";
        if (!bl.orElseThrow().sitzungId().equals(r.zaehlsitzungId())) return "FALSCHE_ZUORDNUNG";
        if (s.orElseThrow().status() != ZaehlsitzungStatus.OFFEN) return "SITZUNG_ABGESCHLOSSEN";
        if (bl.orElseThrow().status() != ZaehlblattStatus.OFFEN) return "BLATT_ABGESCHLOSSEN";
        if (s.orElseThrow().statusVersion() != r.erwarteteSitzungStatusVersion()
                || bl.orElseThrow().statusVersion() != r.erwarteteBlattStatusVersion()) return "STATUSVERSION_VERALTET";
        return null;
    }
    private ErgebnisSpeicherergebnis ausPruefung(RequestPruefergebnis p) {
        return switch (p.status()) {
            case BEKANNTE_ENTSCHEIDUNG -> new ErgebnisSpeicherergebnis(p.entscheidung().entscheidungscode(), p.entscheidung(), true);
            case REQUEST_ID_KONFLIKT -> abgelehnt("REQUEST_ID_REUSED");
            case NICHT_BERECHTIGTER_ZUGRIFF -> abgelehnt("NICHT_BERECHTIGTER_ZUGRIFF");
            case NOCH_IN_BEARBEITUNG -> abgelehnt("NOCH_IN_BEARBEITUNG");
            default -> throw new IllegalStateException("Unerwartete Wiederholungspruefung");
        };
    }
    private ErgebnisSpeicherergebnis abgelehnt(String code) { return new ErgebnisSpeicherergebnis(code, null, false); }
}
