package com.example.myapplication;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import static com.example.myapplication.EinzelpositionsSynchronisierung.*;

// Nur in Testartefakten enthalten; verwendet keine Firmenkonfiguration.
public final class SqlServerEinzelpositionsAdapter implements Server {
    public static final String TESTDATENBANK = "InventurEinzelSync_Isoliert";
    private static final String MARKER = "Inventur-Einzeltest-v1";
    private static final String TOKEN = "0x[0-9A-F]{16}:0x[0-9A-F]{16}";

    public interface Verbindungsquelle { Connection oeffne() throws SQLException; }
    private final Verbindungsquelle quelle;

    public SqlServerEinzelpositionsAdapter(Verbindungsquelle quelle) {
        if (quelle == null) throw new IllegalArgumentException("Isolierte Verbindungsquelle fehlt.");
        this.quelle = quelle;
    }

    @Override public boolean istIsolierterTestbetrieb() { return true; }

    public Stand ladeAusgangsstand(long ergebnisId) throws IOException {
        if (ergebnisId <= 0) throw new IllegalArgumentException("Eindeutige Ergebnis-ID fehlt.");
        try (Connection c = quelle.oeffne()) {
            pruefeUmgebung(c);
            try (CallableStatement s = c.prepareCall("{call [einzeltest].[LeseErgebnis](?)}")) {
                s.setQueryTimeout(15); s.setLong(1, ergebnisId);
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) throw new IOException("Vorhandene Ergebniszuordnung fehlt.");
                    Stand stand = new Stand(r.getInt("BlattNr"), r.getInt("PosNr"), r.getLong("PositionsId"),
                            r.getLong("ErgebnisId"), r.getString("Artikel"), r.getString("Durchgang"),
                            token(r.getString("Token")), menge(r, "Menge"));
                    if (stand.ergebnisId != ergebnisId || r.next()) throw new IOException("Ergebniszuordnung ist nicht eindeutig.");
                    return stand;
                }
            }
        } catch (SQLException | IllegalArgumentException e) { throw fehler(e); }
    }

    @Override public Quittung sucheAuftrag(String id) throws IOException {
        pruefeId(id);
        try (Connection c = quelle.oeffne()) {
            pruefeUmgebung(c);
            try (CallableStatement s = c.prepareCall("{call [einzeltest].[LeseAuftrag](?)}")) {
                s.setQueryTimeout(15); s.setString(1, id);
                Quittung q = leseQuittung(s, true);
                if (q != null && !id.equals(q.auftrag.id)) throw new IOException("Auftragsantwort stimmt nicht ueberein.");
                return q;
            }
        } catch (SQLException | IllegalArgumentException e) { throw fehler(e); }
    }

    @Override public Quittung verarbeite(Auftrag a) throws IOException {
        if (a == null) throw new IllegalArgumentException("Mengenauftrag fehlt.");
        pruefeId(a.id); pruefeText(a.erwartet.artikel); pruefeText(a.erwartet.durchgang); token(a.erwartet.token);
        try (Connection c = quelle.oeffne()) {
            pruefeUmgebung(c);
            // Die Prozedur besitzt die Transaktion; der Client darf keine eigene offen halten.
            try (CallableStatement s = c.prepareCall("{call [einzeltest].[VerarbeiteAuftrag](?,?,?,?,?,?,?,?,?,?,?)}")) {
                s.setQueryTimeout(15);
                s.setString(1, a.id);
                s.setLong(2, a.erwartet.ergebnisId);
                s.setLong(3, a.erwartet.positionsId);
                s.setInt(4, a.erwartet.blatt);
                s.setInt(5, a.erwartet.position);
                s.setString(6, a.erwartet.artikel);
                s.setString(7, a.erwartet.durchgang);
                s.setString(8, a.erwartet.token);
                setzeMenge(s, 9, a.erwartet.menge);
                setzeMenge(s, 10, a.menge);
                s.setLong(11, a.lokaleRevision);
                Quittung q = leseQuittung(s, false);
                if (!a.equals(q.auftrag)) throw new IOException("Auftragsinhalt stimmt nicht ueberein.");
                return q;
            }
        } catch (SQLException | IllegalArgumentException e) { throw fehler(e); }
    }

    private void pruefeUmgebung(Connection c) throws SQLException {
        if (c == null || !TESTDATENBANK.equals(c.getCatalog()) || !c.getAutoCommit()) {
            throw new SQLException("Isolierte Testdatenbank oder Transaktionszustand ungueltig.");
        }
        try (PreparedStatement s = c.prepareStatement("SELECT Kennung FROM [einzeltest].[Umgebung] WHERE Id=1")) {
            s.setQueryTimeout(15);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next() || !MARKER.equals(r.getString(1)) || r.next()) {
                    throw new SQLException("Isolierte Testumgebung nicht nachgewiesen.");
                }
            }
        }
    }

    private Quittung leseQuittung(CallableStatement s, boolean darfFehlen) throws SQLException, IOException {
        try (ResultSet r = s.executeQuery()) {
            if (!r.next()) {
                if (darfFehlen) return null;
                throw new IOException("Dauerhafte Serverquittung fehlt.");
            }
            Stand erwartet = new Stand(r.getInt("BlattNr"), r.getInt("PosNr"), r.getLong("PositionsId"),
                    r.getLong("ErgebnisId"), r.getString("Artikel"), r.getString("Durchgang"),
                    token(r.getString("ErwartetesToken")), menge(r, "ErwarteteMenge"));
            Auftrag a = new Auftrag(r.getString("AuftragId"), erwartet, r.getLong("LokaleRevision"), menge(r, "NeueMenge"));
            String status = r.getString("Status");
            boolean konflikt = "KONFLIKT".equals(status);
            if (!konflikt && !"BESTAETIGT".equals(status)) throw new IOException("Auftrag besitzt keine abgeschlossene Serverentscheidung.");
            Stand gespeichert = konflikt ? null : new Stand(erwartet.blatt, erwartet.position, erwartet.positionsId,
                    erwartet.ergebnisId, erwartet.artikel, erwartet.durchgang,
                    token(r.getString("GespeichertesToken")), menge(r, "GespeicherteMenge"));
            if (r.next()) throw new IOException("Auftragsantwort ist nicht eindeutig.");
            if (!konflikt && (erwartet.token.equals(gespeichert.token) || !java.util.Objects.equals(a.menge, gespeichert.menge))) {
                throw new IOException("Serverquittung bestaetigt die Menge nicht.");
            }
            return new Quittung(a, gespeichert, konflikt);
        }
    }

    private static Integer menge(ResultSet r, String spalte) throws SQLException {
        BigDecimal wert = r.getBigDecimal(spalte);
        if (wert == null) return null;
        try { return wert.intValueExact(); }
        catch (ArithmeticException e) { throw new SQLException("Mengenwert ist nicht verlustfrei als Ganzzahl darstellbar."); }
    }
    private static void setzeMenge(CallableStatement s, int index, Integer wert) throws SQLException {
        if (wert == null) s.setNull(index, Types.INTEGER); else s.setInt(index, wert);
    }
    private static String token(String token) {
        if (token == null || !token.matches(TOKEN)) throw new IllegalArgumentException("Erwarteter Serververgleich fehlt.");
        return token;
    }
    private static void pruefeText(String text) {
        if (text == null || text.trim().isEmpty() || text.length() > 128) throw new IllegalArgumentException("SQL-Zuordnung ungueltig.");
    }
    private static void pruefeId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_.-]{1,128}")) throw new IllegalArgumentException("Auftragskennung ungueltig.");
    }
    private static IOException fehler(Exception e) {
        // Treibermeldungen koennen Verbindungswerte enthalten und werden nicht weitergereicht.
        return new IOException("Isolierte SQL-Server-Operation fehlgeschlagen; Auftragsstatus erneut pruefen.");
    }
}
