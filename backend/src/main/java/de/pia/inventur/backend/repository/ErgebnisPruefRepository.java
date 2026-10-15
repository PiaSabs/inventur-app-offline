package de.pia.inventur.backend.repository;

import java.util.Optional;
import java.util.UUID;
import de.pia.inventur.backend.entity.BenutzerRolle;
import de.pia.inventur.backend.entity.ZaehlsitzungStatus;
import de.pia.inventur.backend.entity.ZaehlblattStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Frische DB-Snapshots unter PostgreSQL-Zeilensperren, unabhaengig vom JPA-Entity-Cache. */
@Repository
public class ErgebnisPruefRepository {

    private final JdbcTemplate jdbc;

    public ErgebnisPruefRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record BenutzerStand(boolean aktiv, BenutzerRolle rolle) {}
    public record ListenStand(UUID zaehlerId, long zuweisungVersion) {}
    public record SitzungsStand(UUID listeId, ZaehlsitzungStatus status, long statusVersion) {}
    public record BlattStand(UUID sitzungId, ZaehlblattStatus status, long statusVersion) {}
    public record PositionsStand(UUID blattId) {}

    public Optional<BenutzerStand> sperreBenutzer(UUID id) {
        return eins("SELECT aktiv, rolle FROM benutzer WHERE id=? FOR SHARE",
                (row, number) -> new BenutzerStand(row.getBoolean("aktiv"), BenutzerRolle.valueOf(row.getString("rolle"))), id);
    }

    public Optional<ListenStand> sperreListe(UUID id) {
        return eins("SELECT aktueller_zaehler_id, zuweisung_version FROM zaehlerliste WHERE id=? FOR SHARE",
                (row, number) -> new ListenStand(row.getObject("aktueller_zaehler_id", UUID.class), row.getLong("zuweisung_version")), id);
    }

    public Optional<SitzungsStand> sperreSitzung(UUID id) {
        return eins("SELECT zaehlerliste_id, status, status_version FROM zaehlsitzung WHERE id=? FOR SHARE",
                (row, number) -> new SitzungsStand(row.getObject("zaehlerliste_id", UUID.class),
                        ZaehlsitzungStatus.valueOf(row.getString("status")), row.getLong("status_version")), id);
    }

    public Optional<BlattStand> sperreBlatt(UUID id) {
        return eins("SELECT zaehlsitzung_id, status, status_version FROM zaehlblatt WHERE id=? FOR SHARE",
                (row, number) -> new BlattStand(row.getObject("zaehlsitzung_id", UUID.class),
                        ZaehlblattStatus.valueOf(row.getString("status")), row.getLong("status_version")), id);
    }

    public Optional<SitzungsStand> sperreSitzungZumAbschluss(UUID id) {
        return eins("SELECT zaehlerliste_id, status, status_version FROM zaehlsitzung WHERE id=? FOR UPDATE",
                (row, number) -> new SitzungsStand(row.getObject("zaehlerliste_id", UUID.class),
                        ZaehlsitzungStatus.valueOf(row.getString("status")), row.getLong("status_version")), id);
    }

    public Optional<BlattStand> sperreBlattZumAbschluss(UUID id) {
        return eins("SELECT zaehlsitzung_id, status, status_version FROM zaehlblatt WHERE id=? FOR UPDATE",
                (row, number) -> new BlattStand(row.getObject("zaehlsitzung_id", UUID.class),
                        ZaehlblattStatus.valueOf(row.getString("status")), row.getLong("status_version")), id);
    }

    public Optional<PositionsStand> sperrePosition(UUID id) {
        return eins("SELECT zaehlblatt_id FROM zaehlposition WHERE id=? FOR UPDATE",
                (row, number) -> new PositionsStand(row.getObject("zaehlblatt_id", UUID.class)), id);
    }

    public Optional<Long> sperreErgebnisrevision(UUID positionId) {
        return eins("SELECT revision FROM zaehlergebnis WHERE zaehlposition_id=? FOR UPDATE",
                (row, number) -> row.getLong("revision"), positionId);
    }

    private <T> Optional<T> eins(String sql, RowMapper<T> mapper, UUID id) {
        return jdbc.query(sql, mapper, id).stream().findFirst();
    }
}
