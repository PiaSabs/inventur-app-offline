package com.example.myapplication;

/** Historischer Klassenname; direkte Datenbankanbindung ist dauerhaft deaktiviert. */
@Deprecated
public final class SqlServerKonfiguration {
    private SqlServerKonfiguration() { }
    public static boolean istVollstaendigKonfiguriert() { return false; }
}
