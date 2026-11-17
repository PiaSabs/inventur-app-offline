package com.example.myapplication;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Alte Schreibwege bleiben gesperrt; internalSql erlaubt kontrollierte Einzelversuche. */
public final class SqlServerSchreibschutz {
    public static final String MELDUNG = BuildConfig.SQL_EINZELVERSUCH_ERLAUBT
            ? "Alte Listen-Schreibwege gesperrt. SQL-Mengenabgleich fuer eindeutig zugeordnete Positionen verfuegbar; unklare Versuche werden nicht wiederholt. Notizen, Kopf und Abschluss bleiben separat offen."
            : "SQL-Testbetrieb: Keine Uebertragung. Aenderungen bleiben lokal gespeichert.";
    private static final Set<String> VERBOTENE_WOERTER = new HashSet<>(Arrays.asList(
            "INSERT", "UPDATE", "DELETE", "MERGE", "INTO", "CREATE", "ALTER", "DROP",
            "TRUNCATE", "EXEC", "EXECUTE", "GRANT", "DENY", "REVOKE", "DBCC", "BACKUP",
            "RESTORE", "BULK", "OPENROWSET", "OPENQUERY", "OPENDATASOURCE", "NEXT", "FOR",
            "SET", "USE", "COMMIT", "ROLLBACK", "SAVE", "BEGIN", "IF", "WHILE", "DECLARE",
            "CHECKPOINT", "SHUTDOWN", "RECONFIGURE", "KILL", "WAITFOR", "RETURN", "GOTO",
            "PRINT", "RAISERROR", "THROW", "ENABLE", "DISABLE", "UPDATETEXT", "WRITETEXT",
            "SEND", "RECEIVE", "CONVERSATION", "REVERT", "DISK"));

    private SqlServerSchreibschutz() { }

    public static boolean istSchreibenErlaubt() { return false; }

    public static boolean istKontrollierterEinzelversuchErlaubt() {
        return BuildConfig.SQL_EINZELVERSUCH_ERLAUBT
                && "internalSql".equals(BuildConfig.BUILD_TYPE)
                && "DatenWHF".equals(BuildConfig.SQL_SERVER_DATABASE)
                && "dbo".equals(BuildConfig.SQL_SERVER_SCHEMA);
    }

    public static void pruefeKontrolliertenEinzelversuch() throws SQLException {
        if (!istKontrollierterEinzelversuchErlaubt()) throw gesperrt();
    }

    public static void pruefeSchreiben() throws RemoteDatenException {
        throw new RemoteDatenException(MELDUNG);
    }

    private static SQLException gesperrt() { return new SQLException(MELDUNG, "25006"); }

    // Nur einzelne SELECT-Abfragen. Literale und maskierte Bezeichner sind keine Befehle.
    static void pruefeLeseabfrage(String sql) throws SQLException {
        if (sql == null) throw gesperrt();
        StringBuilder befehle = new StringBuilder();
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '[' || c == '"') {
                char ende = c == '[' ? ']' : c;
                boolean geschlossen = false;
                for (i++; i < sql.length(); i++) {
                    if (sql.charAt(i) == ende) {
                        if (i + 1 < sql.length() && sql.charAt(i + 1) == ende) { i++; continue; }
                        geschlossen = true;
                        break;
                    }
                }
                if (!geschlossen) throw gesperrt();
                befehle.append(' ');
            } else {
                if (c == ';' || c == '{' || c == '}' || c == '\0'
                        || (i + 1 < sql.length() && ((c == '-' && sql.charAt(i + 1) == '-')
                        || (c == '/' && sql.charAt(i + 1) == '*')))) throw gesperrt();
                befehle.append(c);
            }
        }
        String text = befehle.toString().trim().toUpperCase(Locale.ROOT);
        if (!text.matches("(?s)^SELECT\\b.*")) throw gesperrt();
        for (String wort : text.split("[^A-Z0-9_]+")) {
            if (VERBOTENE_WOERTER.contains(wort)) throw gesperrt();
        }
    }

    public static Connection sichere(Connection verbindung) {
        return (Connection) Proxy.newProxyInstance(SqlServerSchreibschutz.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, methode, argumente) -> {
                    String name = methode.getName();
                    if (methode.getDeclaringClass() == Object.class) return objekt(proxy, methode, argumente);
                    if (name.equals("prepareStatement")) {
                        if (argumente.length != 1) throw gesperrt();
                        String sql = (String) argumente[0];
                        pruefeLeseabfrage(sql);
                        return sichereAnweisung((Statement) rufeAuf(verbindung, methode, argumente),
                                (Connection) proxy, sql);
                    }
                    if (name.equals("createStatement")) {
                        if (argumente != null && argumente.length != 0) throw gesperrt();
                        return sichereAnweisung((Statement) rufeAuf(verbindung, methode, argumente),
                                (Connection) proxy, null);
                    }
                    if (name.equals("isReadOnly")) return true;
                    if (name.equals("setReadOnly")) {
                        if (!Boolean.TRUE.equals(argumente[0])) throw gesperrt();
                        return null;
                    }
                    if (Arrays.asList("close", "isClosed", "isValid", "getWarnings", "clearWarnings",
                            "getAutoCommit", "getCatalog", "getSchema", "getTransactionIsolation",
                            "getNetworkTimeout", "getHoldability", "rollback", "abort").contains(name)) {
                        return rufeAuf(verbindung, methode, argumente);
                    }
                    // Kein unwrap, CallableStatement oder anderer Zugang zur rohen Verbindung.
                    throw gesperrt();
                });
    }

    private static Statement sichereAnweisung(Statement anweisung, Connection verbindung, String sql) {
        Class<?> typ = sql == null ? Statement.class : PreparedStatement.class;
        return (Statement) Proxy.newProxyInstance(SqlServerSchreibschutz.class.getClassLoader(),
                new Class<?>[]{typ}, (proxy, methode, argumente) -> {
                    String name = methode.getName();
                    if (methode.getDeclaringClass() == Object.class) return objekt(proxy, methode, argumente);
                    if (name.equals("getConnection")) return verbindung;
                    if (name.equals("executeQuery")) {
                        if (sql == null) {
                            pruefeLeseabfrage((String) argumente[0]);
                        } else {
                            if (argumente != null && argumente.length != 0) throw gesperrt();
                            pruefeLeseabfrage(sql);
                        }
                        return sichereErgebnisse((ResultSet) rufeAuf(anweisung, methode, argumente), (Statement) proxy);
                    }
                    if (Arrays.asList("close", "isClosed", "cancel", "getWarnings", "clearWarnings",
                            "clearParameters", "setInt", "setLong", "setString", "setNull", "setTimestamp",
                            "setDate", "setBoolean", "setFloat", "setDouble", "setShort", "setByte",
                            "setBigDecimal", "setBytes", "setNString", "setObject", "setQueryTimeout",
                            "setFetchSize", "setMaxRows", "getFetchSize", "getMaxRows", "getQueryTimeout").contains(name)) {
                        return rufeAuf(anweisung, methode, argumente);
                    }
                    // execute, executeUpdate, Batches und Rueckgaben roher JDBC-Objekte bleiben gesperrt.
                    throw gesperrt();
                });
    }

    private static ResultSet sichereErgebnisse(ResultSet ergebnisse, Statement anweisung) {
        if (ergebnisse == null) return null;
        return (ResultSet) Proxy.newProxyInstance(SqlServerSchreibschutz.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, (proxy, methode, argumente) -> {
                    String name = methode.getName();
                    if (methode.getDeclaringClass() == Object.class) return objekt(proxy, methode, argumente);
                    if (name.equals("getStatement")) return anweisung;
                    if (Arrays.asList("next", "close", "isClosed", "wasNull", "getString", "getNString",
                            "getInt", "getLong", "getFloat", "getDouble", "getShort", "getByte", "getBoolean",
                            "getBigDecimal", "getBytes", "getDate", "getTime", "getTimestamp", "getMetaData",
                            "findColumn", "getWarnings", "clearWarnings").contains(name)) {
                        return rufeAuf(ergebnisse, methode, argumente);
                    }
                    if (name.equals("getObject")) {
                        Object wert = rufeAuf(ergebnisse, methode, argumente);
                        if (wert == null || wert instanceof String || wert instanceof Number
                                || wert instanceof Boolean || wert instanceof java.util.Date || wert instanceof byte[]) return wert;
                    }
                    // Auch aktualisierbare ResultSets duerfen keine Daten aendern.
                    throw gesperrt();
                });
    }

    private static Object rufeAuf(Object ziel, Method methode, Object[] argumente) throws Throwable {
        try { return methode.invoke(ziel, argumente); }
        catch (InvocationTargetException exception) { throw exception.getCause(); }
    }

    private static Object objekt(Object proxy, Method methode, Object[] argumente) {
        if (methode.getName().equals("equals")) return proxy == argumente[0];
        if (methode.getName().equals("hashCode")) return System.identityHashCode(proxy);
        return "SQL-Testbetrieb: schreibgeschuetzt";
    }
}
