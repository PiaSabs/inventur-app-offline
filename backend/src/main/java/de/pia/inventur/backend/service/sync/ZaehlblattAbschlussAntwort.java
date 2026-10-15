package de.pia.inventur.backend.service.sync;
import java.time.Instant;
import java.util.UUID;
import de.pia.inventur.backend.entity.ZaehlblattStatus;
import de.pia.inventur.backend.entity.ZaehlsitzungStatus;
public record ZaehlblattAbschlussAntwort(UUID requestId, UUID zaehlblattId, String entscheidung,
        ZaehlblattStatus blattStatus, long blattStatusVersion, long ungezaehltePositionen,
        Instant abgeschlossenAm, Boolean sitzungDurchDiesenVorgangAbgeschlossen,
        ZaehlsitzungStatus sitzungsStatus, Long sitzungsStatusVersion) {
    // Bestehende Aufrufer und alte JSON-Antworten mit sieben Feldern bleiben lesbar.
    // Fehlende Sitzungsfelder bedeuten unbekannt, niemals eine bestaetigte Sitzungsschliessung.
    public ZaehlblattAbschlussAntwort(UUID requestId, UUID zaehlblattId, String entscheidung,
            ZaehlblattStatus blattStatus, long blattStatusVersion, long ungezaehltePositionen,
            Instant abgeschlossenAm) {
        this(requestId, zaehlblattId, entscheidung, blattStatus, blattStatusVersion,
                ungezaehltePositionen, abgeschlossenAm, null, null, null);
    }

    public ZaehlblattAbschlussAntwort mitSitzungsstand(boolean geschlossen, long version) {
        return new ZaehlblattAbschlussAntwort(requestId, zaehlblattId, entscheidung, blattStatus,
                blattStatusVersion, ungezaehltePositionen, abgeschlossenAm, geschlossen,
                geschlossen ? ZaehlsitzungStatus.ABGESCHLOSSEN : ZaehlsitzungStatus.OFFEN, version);
    }
}
