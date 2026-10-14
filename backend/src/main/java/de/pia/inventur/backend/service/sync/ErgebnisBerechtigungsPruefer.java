package de.pia.inventur.backend.service.sync;

import static de.pia.inventur.backend.service.sync.ErgebnisPruefcode.*;

import java.util.Objects;
import de.pia.inventur.backend.entity.BenutzerRolle;
import de.pia.inventur.backend.entity.ZaehlsitzungStatus;
import de.pia.inventur.backend.entity.ZaehlblattStatus;
import de.pia.inventur.backend.repository.ErgebnisPruefRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Interne Autorisierung, keine Authentifizierung. Gueltigkeit nur in der laufenden Transaktion. */
@Service
public class ErgebnisBerechtigungsPruefer {

    private final ErgebnisPruefRepository daten;
    private final ErgebnisRequestNormalisierer normalisierer;

    public ErgebnisBerechtigungsPruefer(ErgebnisPruefRepository daten, ErgebnisRequestNormalisierer normalisierer) {
        this.daten = daten;
        this.normalisierer = normalisierer;
    }

    // Sperrreihenfolge: Benutzer -> Liste -> Sitzung -> Blatt -> Position -> Ergebnis.
    // Fuer neue Requests vor dem Request-INSERT pruefen: dessen FK-Sperren koennen
    // sonst beim nachtraeglichen Hochstufen zur Positionssperre Deadlocks verursachen.
    @Transactional(propagation = Propagation.MANDATORY)
    public ErgebnisPruefergebnis pruefe(GepruefterUrheber urheber, ErgebnisSpeichernInhalt inhalt) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Pruefung erfordert eine aufrufende schreibfaehige Transaktion");
        }
        if (urheber == null || inhalt == null) throw new IllegalArgumentException("Urheber und Inhalt sind erforderlich");
        if (inhalt.zaehlmenge() != null && inhalt.zaehlmenge() < 0) return result(UNGUELTIGE_ZAEHLMENGE);
        try {
            normalisierer.normalisiere(inhalt);
        } catch (IllegalArgumentException invalid) {
            return result(UNGUELTIGER_REQUEST);
        }
        var benutzer = daten.sperreBenutzer(urheber.benutzerId());
        if (benutzer.isEmpty()) return result(BENUTZER_NICHT_VORHANDEN);
        if (!benutzer.orElseThrow().aktiv()) return result(BENUTZER_INAKTIV);
        if (benutzer.orElseThrow().rolle() != BenutzerRolle.ZAEHLER) return result(FALSCHE_ROLLE);

        var liste = daten.sperreListe(inhalt.zaehlerlisteId());
        if (liste.isEmpty()) return result(ZIEL_NICHT_VORHANDEN);
        if (!Objects.equals(liste.orElseThrow().zaehlerId(), urheber.benutzerId())) return result(LISTE_NICHT_ZUGEWIESEN);
        if (liste.orElseThrow().zuweisungVersion() != inhalt.zuweisungVersion()) return result(ZUWEISUNG_GEAENDERT);

        var sitzung = daten.sperreSitzung(inhalt.zaehlsitzungId());
        if (sitzung.isEmpty()) return result(ZIEL_NICHT_VORHANDEN);
        if (!sitzung.orElseThrow().listeId().equals(inhalt.zaehlerlisteId())) return result(FALSCHE_ZUORDNUNG);
        if (sitzung.orElseThrow().status() != ZaehlsitzungStatus.OFFEN) return result(SITZUNG_ABGESCHLOSSEN);

        var blatt = daten.sperreBlatt(inhalt.zaehlblattId());
        if (blatt.isEmpty()) return result(ZIEL_NICHT_VORHANDEN);
        if (!blatt.orElseThrow().sitzungId().equals(inhalt.zaehlsitzungId())) return result(FALSCHE_ZUORDNUNG);
        if (blatt.orElseThrow().status() != ZaehlblattStatus.OFFEN) return result(BLATT_ABGESCHLOSSEN);

        var position = daten.sperrePosition(inhalt.zaehlpositionId());
        if (position.isEmpty()) return result(ZIEL_NICHT_VORHANDEN);
        if (!position.orElseThrow().blattId().equals(inhalt.zaehlblattId())) return result(FALSCHE_ZUORDNUNG);
        if (blatt.orElseThrow().statusVersion() != inhalt.erwarteteBlattStatusVersion()
                || sitzung.orElseThrow().statusVersion() != inhalt.erwarteteSitzungStatusVersion()) return result(STATUSVERSION_VERALTET);

        long revision = daten.sperreErgebnisrevision(inhalt.zaehlpositionId()).orElse(0L);
        if (revision != inhalt.basisRevision()) return result(ERGEBNISREVISION_VERALTET);
        return result(ZULAESSIG);
    }

    /** Vollstaendige Hierarchie vor Archivierung: fruehe Versions-/Statusablehnungen reichen nicht. */
    @Transactional(propagation = Propagation.MANDATORY)
    public ErgebnisPruefergebnis pruefeKonfliktKontext(GepruefterUrheber urheber, ErgebnisSpeichernInhalt inhalt) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Konfliktpruefung erfordert eine schreibfaehige Transaktion");
        }
        var benutzer = daten.sperreBenutzer(urheber.benutzerId());
        if (benutzer.isEmpty()) return result(BENUTZER_NICHT_VORHANDEN);
        if (!benutzer.orElseThrow().aktiv()) return result(BENUTZER_INAKTIV);
        if (benutzer.orElseThrow().rolle() != BenutzerRolle.ZAEHLER) return result(FALSCHE_ROLLE);
        var liste = daten.sperreListe(inhalt.zaehlerlisteId());
        if (liste.isEmpty()) return result(ZIEL_NICHT_VORHANDEN);
        if (!Objects.equals(liste.orElseThrow().zaehlerId(), urheber.benutzerId())) return result(LISTE_NICHT_ZUGEWIESEN);
        var sitzung = daten.sperreSitzung(inhalt.zaehlsitzungId());
        if (sitzung.isEmpty()) return result(ZIEL_NICHT_VORHANDEN);
        if (!sitzung.orElseThrow().listeId().equals(inhalt.zaehlerlisteId())) return result(FALSCHE_ZUORDNUNG);
        var blatt = daten.sperreBlatt(inhalt.zaehlblattId());
        if (blatt.isEmpty()) return result(ZIEL_NICHT_VORHANDEN);
        if (!blatt.orElseThrow().sitzungId().equals(inhalt.zaehlsitzungId())) return result(FALSCHE_ZUORDNUNG);
        var position = daten.sperrePosition(inhalt.zaehlpositionId());
        if (position.isEmpty()) return result(ZIEL_NICHT_VORHANDEN);
        if (!position.orElseThrow().blattId().equals(inhalt.zaehlblattId())) return result(FALSCHE_ZUORDNUNG);
        return result(ZULAESSIG);
    }

    private static ErgebnisPruefergebnis result(ErgebnisPruefcode code) { return new ErgebnisPruefergebnis(code); }
}
