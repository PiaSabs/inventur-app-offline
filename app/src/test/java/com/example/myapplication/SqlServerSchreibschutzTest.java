package com.example.myapplication;

import org.junit.Test;
import java.lang.reflect.Proxy;
import java.sql.*;
import static org.junit.Assert.*;

public class SqlServerSchreibschutzTest {
    private interface SqlAufruf { void ausfuehren() throws Exception; }

    private void erwarteSperre(SqlAufruf aufruf) throws Exception {
        try { aufruf.ausfuehren(); fail("SQL-Aufruf muss gesperrt sein"); }
        catch (SQLException exception) { assertEquals("25006", exception.getSQLState()); }
    }

    @Test public void schreibenIstOhneEinstellungGesperrt() throws Exception {
        assertFalse(SqlServerSchreibschutz.istSchreibenErlaubt());
        ApiInventurRemoteDataSource quelle = new ApiInventurRemoteDataSource(null);
        assertTrue(quelle.istSchreibgeschuetzt());
        try { quelle.speichereZaehlmengen(null); fail("Keine Serverbestaetigung erlaubt"); }
        catch (RemoteDatenException exception) { assertEquals(SqlServerSchreibschutz.MELDUNG, exception.getMessage()); }
        try { quelle.markiereZaehlerlisteAlsAbgeschlossen(1); fail("Kein bestaetigter SQL-Abschluss"); }
        catch (RemoteDatenException exception) { assertEquals(SqlServerSchreibschutz.MELDUNG, exception.getMessage()); }
    }

    @Test public void aenderndeSqlAbfragenErreichenDenTreiberNicht() throws Exception {
        TreiberFake fake = new TreiberFake();
        Connection connection = SqlServerSchreibschutz.sichere(fake.verbindung());
        for (String sql : new String[]{"INSERT INTO artikel VALUES (1)", "UPDATE artikel SET menge=0",
                "DELETE FROM artikel", "MERGE artikel USING quelle ON 1=1 WHEN MATCHED THEN DELETE",
                "TRUNCATE TABLE artikel", "CREATE TABLE test (id int)", "ALTER TABLE test ADD id int",
                "DROP TABLE test", "EXEC test", "BACKUP DATABASE test TO DISK='test'",
                "SELECT * INTO kopie FROM artikel", "SELECT NEXT VALUE FOR nummern",
                "SELECT 1; DELETE FROM artikel", "SELECT 1\nUPDATE artikel SET menge=0",
                "SELECT 1\nSET IDENTITY_INSERT artikel ON", "SELECT 1\nCHECKPOINT",
                "SELECT 1\nRECEIVE TOP(1) * FROM testqueue", "SELECT 1\nEND CONVERSATION @handle",
                "WITH daten AS (SELECT 1 AS id) DELETE FROM artikel", "SELECT * FROM OPENQUERY(test,'UPDATE x SET y=0')"}) {
            erwarteSperre(() -> connection.prepareStatement(sql));
            try (Statement statement = connection.createStatement()) {
                erwarteSperre(() -> statement.executeQuery(sql));
            }
        }
        assertEquals(0, fake.vorbereitet);
        assertEquals(0, fake.ausgefuehrt);
    }

    @Test public void direkteSchreibUndBatchMethodenSindGesperrt() throws Exception {
        TreiberFake fake = new TreiberFake();
        Connection connection = SqlServerSchreibschutz.sichere(fake.verbindung());
        try (Statement statement = connection.createStatement()) {
            erwarteSperre(() -> statement.executeUpdate("UPDATE artikel SET menge=0"));
            erwarteSperre(() -> statement.execute("SELECT 1"));
            erwarteSperre(() -> statement.addBatch("UPDATE artikel SET menge=0"));
            erwarteSperre(statement::executeBatch);
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
            erwarteSperre(statement::executeUpdate);
            erwarteSperre(statement::execute);
            erwarteSperre(statement::addBatch);
            erwarteSperre(statement::executeBatch);
            erwarteSperre(() -> statement.executeQuery("DELETE FROM artikel"));
        }
        assertEquals(0, fake.ausgefuehrt);
    }

    @Test public void leseabfragenUndParameterFunktionierenWeiter() throws Exception {
        TreiberFake fake = new TreiberFake();
        try (Connection connection = SqlServerSchreibschutz.sichere(fake.verbindung());
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT z.*, CASE WHEN t.HinweisIntern='Demo' THEN t.Bezeichnung2 ELSE z.Lagerort END AS Ort "
                             + "FROM [dbo].[Z\u00e4hlliste] z LEFT JOIN Teilestamm t ON t.Identnummer=z.IdentNr WHERE z.BlattNr=?")) {
            statement.setInt(1, 99001);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(Integer.valueOf(0), result.getObject(1));
                assertNull(result.getObject(2));
            }
            assertEquals(1, fake.vorbereitet);
            assertEquals(1, fake.ausgefuehrt);
        }
    }

    @Test public void literaleUndMaskierteBezeichnerBleibenLesbar() throws Exception {
        SqlServerSchreibschutz.pruefeLeseabfrage("SELECT [UPDATE], [a]]b], 'DELETE; -- ''Test''' FROM [dbo].[Test]");
        SqlServerSchreibschutz.pruefeLeseabfrage("SELECT TOP 0 * FROM [dbo].[Bestandszaehlungen]");
        SqlServerSchreibschutz.pruefeLeseabfrage("SELECT schema_name FROM (SELECT s.name AS schema_name FROM sys.schemas s UNION ALL SELECT s.name FROM sys.schemas s) daten");
        for (String sql : new String[]{"SELECT 1 -- Test", "SELECT /* Test */ 1", "SELECT 'offen", "SELECT [offen", "{call test}", ""}) {
            erwarteSperre(() -> SqlServerSchreibschutz.pruefeLeseabfrage(sql));
        }
    }

    @Test public void verbindungenKoennenNichtEntpacktOderUmgeschaltetWerden() throws Exception {
        TreiberFake fake = new TreiberFake();
        Connection connection = SqlServerSchreibschutz.sichere(fake.verbindung());
        erwarteSperre(() -> connection.unwrap(Connection.class));
        erwarteSperre(() -> connection.prepareCall("{call test}"));
        erwarteSperre(() -> connection.setReadOnly(false));
        erwarteSperre(() -> connection.prepareStatement("SELECT 1", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE));
        assertTrue(connection.isReadOnly());
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1"); ResultSet result = statement.executeQuery()) {
            assertSame(connection, statement.getConnection());
            assertSame(statement, result.getStatement());
            assertSame(connection, result.getStatement().getConnection());
            erwarteSperre(() -> statement.unwrap(PreparedStatement.class));
            erwarteSperre(() -> result.unwrap(ResultSet.class));
            erwarteSperre(() -> result.updateInt(1, 0));
            erwarteSperre(result::updateRow);
            erwarteSperre(result::insertRow);
            erwarteSperre(result::deleteRow);
            erwarteSperre(() -> result.getClob(1));
        }
        assertEquals(1, fake.ausgefuehrt);
    }

    private static final class TreiberFake {
        int vorbereitet;
        int ausgefuehrt;

        Connection verbindung() {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement")) { vorbereitet++; return anweisung(PreparedStatement.class); }
                        if (method.getName().equals("createStatement")) return anweisung(Statement.class);
                        return standard(method.getReturnType());
                    });
        }

        Object anweisung(Class<?> type) {
            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                if (method.getName().startsWith("execute")) {
                    ausgefuehrt++;
                    if (method.getName().equals("executeQuery")) return ergebnisse();
                }
                return standard(method.getReturnType());
            });
        }

        ResultSet ergebnisse() {
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("next")) return true;
                        if (method.getName().equals("getObject")) return args[0].equals(1) ? Integer.valueOf(0) : null;
                        return standard(method.getReturnType());
                    });
        }

        Object standard(Class<?> type) {
            if (type == boolean.class) return false;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            return null;
        }
    }
}
