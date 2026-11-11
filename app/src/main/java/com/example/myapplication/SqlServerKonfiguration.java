package com.example.myapplication;

public final class SqlServerKonfiguration {

    public static final String HOST = BuildConfig.SQL_SERVER_HOST;
    public static final int PORT = BuildConfig.SQL_SERVER_PORT;
    public static final String DATENBANK = BuildConfig.SQL_SERVER_DATABASE;
    public static final String SCHEMA = BuildConfig.SQL_SERVER_SCHEMA;
    public static final String BENUTZERNAME = BuildConfig.SQL_SERVER_USERNAME;
    public static final String PASSWORT = BuildConfig.SQL_SERVER_PASSWORD;

    private SqlServerKonfiguration() {
    }

    public static boolean istVollstaendigKonfiguriert() {
        return !HOST.trim().isEmpty()
                && !DATENBANK.trim().isEmpty()
                && !SCHEMA.trim().isEmpty()
                && !BENUTZERNAME.trim().isEmpty()
                && !PASSWORT.trim().isEmpty();
    }
}
