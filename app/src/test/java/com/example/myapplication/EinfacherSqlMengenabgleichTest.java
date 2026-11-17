package com.example.myapplication;

import org.junit.Test;
import java.math.BigDecimal;
import java.io.IOException;
import static org.junit.Assert.*;
import static com.example.myapplication.EinzelpositionsSynchronisierung.*;

public class EinfacherSqlMengenabgleichTest {
    static class LokalFake implements Lokal {
        Auftrag a;String zustand="VORBEREITET";int versuche,quittierungen;boolean quittierFehler;
        LokalFake(Auftrag a){this.a=a;}
        public Auftrag bereiteMengenauftragVor(int b,int p){return "BESTAETIGT".equals(zustand)?null:a;}
        public Status pruefeEinmalversuch(Auftrag a){return "VORBEREITET".equals(zustand)?Status.NICHTS_ZU_TUN:"KONFLIKT".equals(zustand)?Status.KONFLIKT:Status.AUSSTEHEND;}
        public void merkeMengenversuch(Auftrag a){zustand="UNKLAR";versuche++;}
        public void quittiereMengenauftrag(Auftrag a,Stand s){if(quittierFehler)throw new IllegalStateException();zustand="BESTAETIGT";quittierungen++;}
        public void merkeMengenkonflikt(Auftrag a){zustand="KONFLIKT";}
    }
    private Auftrag auftrag(SqlServerMengenSchreibversuchTest.Db d,Integer menge)throws Exception {
        return new Auftrag("SQL-MENGEN-TEST",SqlServerMengenSchreibversuch.alsMengenstand(d.api().lese(99001,1)),3,menge);
    }
    private Status run(LokalFake l,SqlServerMengenSchreibversuchTest.Db d){return new EinzelpositionsSynchronisierung(l,d.api()).synchronisiere(99001,1);}
    @Test public void echterJdbcAblaufMitFakeQuittiertPassendenAuftrag()throws Exception {
        SqlServerMengenSchreibversuchTest.Db d=new SqlServerMengenSchreibversuchTest.Db();d.value=BigDecimal.valueOf(7);
        LokalFake l=new LokalFake(auftrag(d,12));assertEquals(Status.BESTAETIGT,run(l,d));
        assertEquals(BigDecimal.valueOf(12),d.value);assertEquals(1,d.updates);assertEquals(1,l.quittierungen);
        assertTrue(d.readSql.contains("AND b.Datum=?"));assertEquals(java.sql.Timestamp.valueOf("2020-01-01 00:00:00"),d.readParameters.get(3));
    }
    @Test public void nullUndNullmengeSindGetrennteUebertragungen()throws Exception {
        SqlServerMengenSchreibversuchTest.Db d=new SqlServerMengenSchreibversuchTest.Db();
        LokalFake zero=new LokalFake(auftrag(d,0));assertEquals(Status.BESTAETIGT,run(zero,d));assertEquals(BigDecimal.ZERO,d.value);
        LokalFake leer=new LokalFake(auftrag(d,null));assertEquals(Status.BESTAETIGT,run(leer,d));assertNull(d.value);
    }
    @Test public void unerwarteterServerwertFuehrtZuKonfliktOhneUpdate()throws Exception {
        SqlServerMengenSchreibversuchTest.Db d=new SqlServerMengenSchreibversuchTest.Db();d.value=BigDecimal.valueOf(7);
        LokalFake l=new LokalFake(auftrag(d,12));d.value=BigDecimal.valueOf(8);
        assertEquals(Status.KONFLIKT,run(l,d));assertEquals(0,d.updates);assertEquals(0,l.quittierungen);
    }
    @Test public void unklarerCommitWirdAuchMitNeuemDienstNichtWiederholt()throws Exception {
        SqlServerMengenSchreibversuchTest.Db d=new SqlServerMengenSchreibversuchTest.Db();LokalFake l=new LokalFake(auftrag(d,12));d.commitError=true;
        assertEquals(Status.AUSSTEHEND,run(l,d));assertEquals(BigDecimal.valueOf(12),d.value);int verbindungen=d.opens;
        assertEquals(Status.AUSSTEHEND,run(l,d));assertEquals(verbindungen,d.opens);assertEquals(1,d.updates);assertEquals(0,l.quittierungen);
    }
    @Test public void ruecklesefehlerNachCommitBleibtOffen()throws Exception {
        SqlServerMengenSchreibversuchTest.Db d=new SqlServerMengenSchreibversuchTest.Db();LokalFake l=new LokalFake(auftrag(d,12));d.postCommitReadError=true;
        assertEquals(Status.AUSSTEHEND,run(l,d));assertEquals(0,l.quittierungen);assertEquals(1,d.updates);
        assertEquals(Status.AUSSTEHEND,run(l,d));assertEquals(1,d.updates);
    }
    @Test public void lokalerQuittierungsfehlerFuehrtZuKeinemZweitenSqlUpdate()throws Exception {
        SqlServerMengenSchreibversuchTest.Db d=new SqlServerMengenSchreibversuchTest.Db();LokalFake l=new LokalFake(auftrag(d,12));l.quittierFehler=true;
        assertEquals(Status.AUSSTEHEND,run(l,d));l.quittierFehler=false;
        assertEquals(Status.AUSSTEHEND,run(l,d));assertEquals(1,d.updates);assertEquals(0,l.quittierungen);
    }
    @Test public void schonGleicheMengeBrauchtKeinUpdate()throws Exception {
        SqlServerMengenSchreibversuchTest.Db d=new SqlServerMengenSchreibversuchTest.Db();d.value=BigDecimal.valueOf(7);
        LokalFake l=new LokalFake(auftrag(d,7));assertEquals(Status.BESTAETIGT,run(l,d));assertEquals(0,d.updates);assertEquals(1,l.quittierungen);
    }
    @Test public void sqlFehlerVorCommitQuittiertKeineMenge()throws Exception {
        SqlServerMengenSchreibversuchTest.Db d=new SqlServerMengenSchreibversuchTest.Db();LokalFake l=new LokalFake(auftrag(d,12));d.writeError=true;
        assertEquals(Status.AUSSTEHEND,run(l,d));assertNull(d.value);assertEquals(0,d.commits);assertEquals(0,l.quittierungen);
    }
    @Test public void doppelteSqlPositionszeileIstKeineEindeutigeZuordnung()throws Exception {
        SqlServerMengenSchreibversuchTest.Db d=new SqlServerMengenSchreibversuchTest.Db();LokalFake l=new LokalFake(auftrag(d,12));d.positionCount=2;
        assertEquals(Status.AUSSTEHEND,run(l,d));assertEquals(0,d.updates);assertEquals(0,l.quittierungen);
    }
    @Test public void vorhandeneQuittierungLoestKeinWeiteresUpdateAus()throws Exception {
        SqlServerMengenSchreibversuchTest.Db d=new SqlServerMengenSchreibversuchTest.Db();LokalFake l=new LokalFake(auftrag(d,12));
        assertEquals(Status.BESTAETIGT,run(l,d));assertEquals(Status.NICHTS_ZU_TUN,run(l,d));assertEquals(1,d.updates);
    }
    @Test public void keineServerAuftragssucheWirdVorgetaeuscht()throws Exception {
        SqlServerMengenSchreibversuchTest.Db d=new SqlServerMengenSchreibversuchTest.Db();
        assertFalse(d.api().hatDauerhafteAuftragsquittung());assertFalse(d.api().istIsolierterTestbetrieb());
        try{d.api().sucheAuftrag("TEST");fail();}catch(IOException expected){}
    }
    @Test public void mengenstandRundreiseBewahrtErgebnisUndPositionsId()throws Exception {
        SqlServerMengenSchreibversuchTest.Db d=new SqlServerMengenSchreibversuchTest.Db();Stand s=auftrag(d,12).erwartet;
        assertEquals(s,SqlServerMengenSchreibversuch.alsMengenstand(SqlServerMengenSchreibversuch.ausMengenstand(s)));
        assertNotEquals(s.positionsId,s.ergebnisId);
    }
    @Test public void offlineBleibtLokalOffenUndWirdNichtAutomatischWiederholt()throws Exception {
        SqlServerMengenSchreibversuchTest.Db d=new SqlServerMengenSchreibversuchTest.Db();LokalFake l=new LokalFake(auftrag(d,12));
        SqlServerMengenSchreibversuch offline=new SqlServerMengenSchreibversuch(()->{throw new java.sql.SQLException("VERTRAULICH","08001");},true);
        assertEquals(Status.AUSSTEHEND,new EinzelpositionsSynchronisierung(l,offline).synchronisiere(99001,1));
        assertEquals(Status.AUSSTEHEND,run(l,d));assertEquals(0,d.updates);assertEquals(0,l.quittierungen);
    }
    @Test public void bestaetigungLiestNachCommitEineNeueVerbindung() throws Exception {
        SqlServerMengenSchreibversuchTest.Db d=new SqlServerMengenSchreibversuchTest.Db();
        LokalFake l=new LokalFake(auftrag(d,12));
        assertEquals(Status.BESTAETIGT,run(l,d));
        assertEquals(3,d.opens);assertEquals(1,d.updates);assertEquals(1,l.quittierungen);
    }
    @Test public void fehlerDerNeuenVerbindungQuittiertNichtUndWiederholtNicht() throws Exception {
        SqlServerMengenSchreibversuchTest.Db d=new SqlServerMengenSchreibversuchTest.Db();
        LokalFake l=new LokalFake(auftrag(d,12));d.newConnectionReadError=true;
        assertEquals(Status.AUSSTEHEND,run(l,d));assertEquals(0,l.quittierungen);assertEquals(1,d.updates);
        int verbindungen=d.opens;assertEquals(Status.AUSSTEHEND,run(l,d));assertEquals(verbindungen,d.opens);
    }
    @Test public void fremdaenderungVorNeuemRuecklesenBleibtUnquittiert() throws Exception {
        SqlServerMengenSchreibversuchTest.Db d=new SqlServerMengenSchreibversuchTest.Db();
        LokalFake l=new LokalFake(auftrag(d,12));d.changeOnNewConnection=true;
        assertEquals(Status.AUSSTEHEND,run(l,d));assertEquals(0,l.quittierungen);assertEquals(1,d.updates);
    }

}
