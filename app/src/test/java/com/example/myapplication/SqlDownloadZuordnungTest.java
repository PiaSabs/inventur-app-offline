package com.example.myapplication;

import org.junit.Test;
import java.sql.SQLException;
import static org.junit.Assert.*;

public class SqlDownloadZuordnungTest {
    private ArtikelDto artikel() {return new ArtikelDto(1,"SYNTH-A","Test","L1",3,null,1,99,"",11L,99001);}
    @Test public void ergebnisIdUndPositionsIdWerdenGetrenntTransportiert()throws Exception {
        SqlServerMengenSchreibversuchTest.Db d=new SqlServerMengenSchreibversuchTest.Db();d.value=java.math.BigDecimal.valueOf(7);
        ArtikelDto a=artikel().mitSqlErgebnis(d.api().leseAmStichtag(99001,1,"2020-01-01"));
        assertEquals(Long.valueOf(11),a.getSqlPositionsId());assertEquals(22,a.getSqlErgebnis().ergebnisId);assertEquals(Integer.valueOf(7),a.getZaehlMenge());
    }
    @Test public void nullUndNullmengeBleibenBeimDownloadVerschieden()throws Exception {
        SqlServerMengenSchreibversuchTest.Db d=new SqlServerMengenSchreibversuchTest.Db();
        assertNull(artikel().mitSqlErgebnis(d.api().lese(99001,1)).getZaehlMenge());d.value=java.math.BigDecimal.ZERO;
        assertEquals(Integer.valueOf(0),artikel().mitSqlErgebnis(d.api().lese(99001,1)).getZaehlMenge());
    }
    @Test public void ergebnisEinesAnderenArtikelsWirdNichtZugeordnet()throws Exception {
        SqlServerMengenSchreibversuchTest.Db d=new SqlServerMengenSchreibversuchTest.Db();
        ArtikelDto a=new ArtikelDto(1,"ANDERER","Test","L1",3,null,1,99,"",11L,99001);
        try{a.mitSqlErgebnis(d.api().lese(99001,1));fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void stichtagWirdAlsTimestampUndTagesbereichGebunden()throws Exception {
        SqlServerMengenSchreibversuchTest.Db d=new SqlServerMengenSchreibversuchTest.Db();d.api().leseAmStichtag(99001,1,"2020-01-01");
        assertTrue(d.readSql.contains("DATEADD(day,1,?)"));assertEquals(java.sql.Timestamp.valueOf("2020-01-01 00:00:00"),d.readParameters.get(3));
        assertEquals(d.readParameters.get(3),d.readParameters.get(4));assertEquals(0,d.updates);
    }
    @Test public void mehrereErgebnisseAmStichtagErteilenKeineFreigabe()throws Exception {
        SqlServerMengenSchreibversuchTest.Db d=new SqlServerMengenSchreibversuchTest.Db();d.duplicate=true;
        try{d.api().leseAmStichtag(99001,1,"2020-01-01");fail();}catch(SQLException e){assertEquals("23000",e.getSQLState());}
    }
    @Test public void bruchmengeWirdNichtAlsGanzzahligerAusgangswertGerundet()throws Exception {
        SqlServerMengenSchreibversuchTest.Db d=new SqlServerMengenSchreibversuchTest.Db();d.value=new java.math.BigDecimal("1.5");
        try{artikel().mitSqlErgebnis(d.api().lese(99001,1));fail();}catch(ArithmeticException expected){}
    }
}
