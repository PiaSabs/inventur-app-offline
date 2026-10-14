package de.pia.inventur.backend.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import de.pia.inventur.backend.service.sync.ErgebnisSpeichernAntwort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Schreibzugriff nach der bestehenden Sperrpruefung, auf derselben JPA/JDBC-Verbindung. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class ErgebnisSpeicherRepository {
    private final JdbcTemplate jdbc;

    public ErgebnisSpeicherRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Instant serverzeit() {
        return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
    }

    public void speichereAktuelles(ErgebnisSpeichernAntwort antwort, UUID urheber, long basisRevision) {
        var zeit = antwort.geaendertAm().atOffset(ZoneOffset.UTC);
        int aktualisiert = jdbc.update("""
                UPDATE zaehlergebnis SET zaehlmenge=?, anmerkung=?, revision=?, geaendert_von=?, geaendert_am=?
                WHERE zaehlposition_id=? AND revision=?
                """, antwort.zaehlmenge(), antwort.anmerkung(), antwort.ergebnisRevision(), urheber, zeit,
                antwort.zaehlpositionId(), basisRevision);
        if (aktualisiert == 1) return;
        if (aktualisiert != 0 || basisRevision != 0) throw new IllegalStateException("Gesperrtes Ergebnis wurde veraendert");
        genauEine(jdbc.update("""
                INSERT INTO zaehlergebnis(id, zaehlposition_id, zaehlmenge, anmerkung, revision, geaendert_von, geaendert_am)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), antwort.zaehlpositionId(), antwort.zaehlmenge(), antwort.anmerkung(),
                antwort.ergebnisRevision(), urheber, zeit));
    }

    /** Ausschliesslich INSERT: fruehere Revisionen werden hier nie geaendert. */
    public void ergaenzeHistorie(ErgebnisSpeichernAntwort antwort, UUID urheber) {
        genauEine(jdbc.update("""
                INSERT INTO ergebnis_historie(id, zaehlposition_id, request_id, revision, zaehlmenge,
                    anmerkung, benutzer_id, geaendert_am) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), antwort.zaehlpositionId(), antwort.requestId(), antwort.ergebnisRevision(),
                antwort.zaehlmenge(), antwort.anmerkung(), urheber, antwort.geaendertAm().atOffset(ZoneOffset.UTC)));
    }

    public void akzeptiereRequest(ErgebnisSpeichernAntwort antwort, String json) {
        genauEine(jdbc.update("""
                UPDATE sync_request SET status='AKZEPTIERT', entscheidungscode='AKZEPTIERT',
                    antwort_http_status=200, antwort_json=?, verarbeitet_am=?
                WHERE request_id=? AND status='EINGEGANGEN'
                """, json, antwort.geaendertAm().atOffset(ZoneOffset.UTC), antwort.requestId()));
    }

    public void lehneArchiviertenRequestAb(UUID requestId, String code, String json, Instant zeit) {
        genauEine(jdbc.update("""
                UPDATE sync_request SET status='ABGELEHNT', entscheidungscode=?,
                    antwort_http_status=409, antwort_json=?, verarbeitet_am=?
                WHERE request_id=? AND status='EINGEGANGEN'
                """, code, json, zeit.atOffset(ZoneOffset.UTC), requestId));
    }

    private void genauEine(int anzahl) {
        if (anzahl != 1) throw new IllegalStateException("Genau eine gespeicherte Zeile erwartet");
    }
}
