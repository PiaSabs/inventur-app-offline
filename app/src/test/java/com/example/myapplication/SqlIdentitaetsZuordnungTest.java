package com.example.myapplication;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static com.example.myapplication.SqlIdentitaetsZuordnung.*;

public class SqlIdentitaetsZuordnungTest {
    private SqlServerMengenSchreibversuch.Ausgangsstand stand() throws Exception {
        return new SqlServerMengenSchreibversuchTest.Db().api().lese(99001,1);
    }
    private Lokal lokal(String artikel,String tag,String fingerprint) {
        return new Lokal(99001,1,artikel,tag,null,null,null,920000,fingerprint);
    }
    private List<SqlPosition> positionen() {return Collections.singletonList(new SqlPosition(11L,99001,1,"SYNTH-A"));}
    private void abgewiesen(Runnable r) {try {r.run();fail("Zuordnung muss abgewiesen werden.");}catch(IllegalArgumentException expected){}}
    @Test public void eindeutigeIdentitaetenBleibenGetrennt() throws Exception {
        Vorschlag v=pruefe(lokal("SYNTH-A","01.01.2020","a"),positionen(),stand());
        assertEquals(11,v.server.positionsId);assertEquals(22,v.server.ergebnisId);assertEquals(Integer.valueOf(920000),v.lokal.menge);
        assertTrue(v.stichtagGleich);assertTrue(v.beschreibung().contains("fachlich noch zu bestaetigen"));
    }
    @Test public void doppeltePositionWirdAbgelehnt() throws Exception {
        SqlServerMengenSchreibversuch.Ausgangsstand s=stand();
        abgewiesen(()->pruefe(lokal("SYNTH-A","01.01.2020","a"),Arrays.asList(new SqlPosition(11L,99001,1,"SYNTH-A"),new SqlPosition(12L,99001,1,"SYNTH-A")),s));
    }
    @Test public void doppelteSqlIdAuchInAnderemBlattWirdAbgelehnt() throws Exception {
        SqlServerMengenSchreibversuch.Ausgangsstand s=stand();
        abgewiesen(()->pruefe(lokal("SYNTH-A","01.01.2020","a"),Arrays.asList(new SqlPosition(11L,99001,1,"SYNTH-A"),new SqlPosition(11L,99002,2,"SYNTH-B")),s));
    }
    @Test public void abweichenderArtikelWirdNichtNachPositionsnummerZugeordnet() throws Exception {
        SqlServerMengenSchreibversuch.Ausgangsstand s=stand();abgewiesen(()->pruefe(lokal("ANDERER","01.01.2020","a"),positionen(),s));
    }
    @Test public void fehlendeErgebniszeileWirdAbgelehnt() {abgewiesen(()->pruefe(lokal("SYNTH-A","01.01.2020","a"),positionen(),null));}
    @Test public void mehrereErgebniszeilenSindKeineEindeutigeZuordnung() throws Exception {
        SqlServerMengenSchreibversuchTest.Db d=new SqlServerMengenSchreibversuchTest.Db();d.duplicate=true;
        try {d.api().lese(99001,1);fail();}catch(java.sql.SQLException expected){}
    }
    @Test public void unbekannterDurchgangErlaubtNurIdentitaetsvorschlag() throws Exception {
        Vorschlag v=pruefe(lokal("SYNTH-A","","a"),positionen(),stand());assertFalse(v.stichtagGleich);
        pruefeBestaetigung(v,v,false);abgewiesen(()->pruefeBestaetigung(v,v,true));
    }
    @Test public void abweichenderStichtagWirdNichtUmgeschrieben() throws Exception {
        Vorschlag v=pruefe(lokal("SYNTH-A","02.01.2020","a"),positionen(),stand());
        assertFalse(v.stichtagGleich);assertEquals("02.01.2020",v.lokal.stichtag);abgewiesen(()->pruefeBestaetigung(v,v,true));
    }
    @Test public void geaenderterLokalerStandVerwirftBestaetigung() throws Exception {
        Vorschlag a=pruefe(lokal("SYNTH-A","01.01.2020","vorher"),positionen(),stand());
        Vorschlag b=pruefe(lokal("SYNTH-A","01.01.2020","nachher"),positionen(),stand());
        abgewiesen(()->pruefeBestaetigung(a,b,true));
    }
    @Test public void vorhandeneWiderspruechlicheIdWirdNichtErsetzt() throws Exception {
        SqlServerMengenSchreibversuch.Ausgangsstand s=stand();
        Lokal l=new Lokal(99001,1,"SYNTH-A","01.01.2020",null,99L,99001,7,"a");
        abgewiesen(()->pruefe(l,positionen(),s));
    }
    @Test public void keineMassenfreigabeWeitererPositionen() throws Exception {
        SqlServerMengenSchreibversuch.Ausgangsstand s=stand();
        Lokal l=new Lokal(99002,1,"SYNTH-A","01.01.2020",null,null,null,7,"a");
        abgewiesen(()->pruefe(l,positionen(),s));
    }
    @Test public void ungueltigesDatumIstKeineDurchgangsbestaetigung() throws Exception {
        Vorschlag v=pruefe(lokal("SYNTH-A","32.01.2020","a"),positionen(),stand());assertFalse(v.stichtagGleich);
    }
}
