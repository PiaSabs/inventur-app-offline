package com.example.myapplication;

import org.junit.Test;
import static org.junit.Assert.*;

public class BuildVeroeffentlichungTest {
    @Test public void oeffentlicheVariantenEnthaltenKeineSqlZugangsdaten() {
        if ("internalSql".equals(BuildConfig.BUILD_TYPE)) {
            assertTrue("Interne Variante braucht ihre lokale Konfiguration", SqlServerKonfiguration.istVollstaendigKonfiguriert());
        } else {
            // Keine Ist-Werte im Fehlerprotokoll ausgeben.
            assertTrue("SQL-Host muss leer sein", BuildConfig.SQL_SERVER_HOST.isEmpty());
            assertTrue("SQL-Datenbank muss leer sein", BuildConfig.SQL_SERVER_DATABASE.isEmpty());
            assertTrue("SQL-Schema muss leer sein", BuildConfig.SQL_SERVER_SCHEMA.isEmpty());
            assertTrue("SQL-Benutzer muss leer sein", BuildConfig.SQL_SERVER_USERNAME.isEmpty());
            assertTrue("SQL-Passwort muss leer sein", BuildConfig.SQL_SERVER_PASSWORD.isEmpty());
            assertFalse("Keine unkonfigurierte SQL-Verbindung erlauben", SqlServerKonfiguration.istVollstaendigKonfiguriert());
        }
    }
}
