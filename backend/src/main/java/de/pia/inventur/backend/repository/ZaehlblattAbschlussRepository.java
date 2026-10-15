package de.pia.inventur.backend.repository;
import java.util.UUID;
import java.time.ZoneOffset;
import java.time.Instant;
import de.pia.inventur.backend.service.sync.ZaehlblattAbschlussAntwort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class ZaehlblattAbschlussRepository {
    private final JdbcTemplate jdbc;
    public ZaehlblattAbschlussRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public long ungezaehltePositionen(UUID blattId) {
        // LEFT JOIN erfasst auch fehlende Ergebniszeilen; 0 ist gezaehlt.
        return jdbc.queryForObject("""
                SELECT count(*) FROM zaehlposition p LEFT JOIN zaehlergebnis e ON e.zaehlposition_id=p.id
                WHERE p.zaehlblatt_id=? AND e.zaehlmenge IS NULL
                """, Long.class, blattId);
    }
    public void schliesse(ZaehlblattAbschlussAntwort a, UUID benutzer, long version) {
        genauEine(jdbc.update("""
                UPDATE zaehlblatt SET status='ABGESCHLOSSEN', status_version=?, abgeschlossen_am=?,
                    geschlossen_von=?, letzter_status_request_id=?
                WHERE id=? AND status='OFFEN' AND status_version=?
                """, a.blattStatusVersion(), a.abgeschlossenAm().atOffset(ZoneOffset.UTC),
                benutzer, a.requestId(), a.zaehlblattId(), version));
    }
    /**
     * Nur nach Blattabschluss und unter der exklusiven Sitzungssperre aufrufen.
     * EXISTS verhindert den leeren Sonderfall, NOT EXISTS verlangt ausschliesslich geschlossene Blaetter.
     * Die Aenderung gehoert zum bereits registrierten letzten Blattrequest, keine eigene Request-ID.
     */
    public boolean schliesseSitzungFallsVollstaendig(UUID sitzungId, long erwarteteVersion, Instant zeit) {
        boolean vollstaendig = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM zaehlblatt WHERE zaehlsitzung_id=?)
                   AND NOT EXISTS (SELECT 1 FROM zaehlblatt
                       WHERE zaehlsitzung_id=? AND status <> 'ABGESCHLOSSEN')
                """, Boolean.class, sitzungId, sitzungId));
        if (!vollstaendig) return false;
        genauEine(jdbc.update("""
                UPDATE zaehlsitzung SET status='ABGESCHLOSSEN', status_version=?, abgeschlossen_am=?
                WHERE id=? AND status='OFFEN' AND status_version=?
                """, Math.addExact(erwarteteVersion, 1), zeit.atOffset(ZoneOffset.UTC), sitzungId, erwarteteVersion));
        return true;
    }

    public void akzeptiere(ZaehlblattAbschlussAntwort a, String json) {
        genauEine(jdbc.update("""
                UPDATE sync_request SET status='AKZEPTIERT', entscheidungscode='AKZEPTIERT',
                    antwort_http_status=200, antwort_json=?, verarbeitet_am=?
                WHERE request_id=? AND status='EINGEGANGEN' AND operation='BLATT_ABSCHLIESSEN'
                """, json, a.abgeschlossenAm().atOffset(ZoneOffset.UTC), a.requestId()));
    }
    private void genauEine(int anzahl) {
        if (anzahl != 1) throw new IllegalStateException("Genau eine gesperrte Zeile erwartet");
    }
}
