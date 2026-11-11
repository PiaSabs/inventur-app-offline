package com.example.myapplication;

import org.junit.Test;
import static org.junit.Assert.*;

public class SqlServerDatumTest {
    @Test public void lokalerAnzeigeStichtagWirdSqlDatum() {
        assertEquals("2026-11-11", ApiInventurRemoteDataSource.parseSqlDatumNachSqlLite("11.11.2026"));
    }

    @Test public void sqlZeitstempelBehaeltSeinenTag() {
        assertEquals("2026-11-11", ApiInventurRemoteDataSource.parseSqlDatumNachSqlLite("2026-11-11 00:00:00.0"));
    }

    @Test public void fehlenderStichtagBleibtLeer() {
        assertNull(ApiInventurRemoteDataSource.parseSqlDatumNachSqlLite(null));
        assertNull(ApiInventurRemoteDataSource.parseSqlDatumNachSqlLite(" "));
    }
}
