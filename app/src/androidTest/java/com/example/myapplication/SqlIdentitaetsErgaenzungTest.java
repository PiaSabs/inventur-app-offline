package com.example.myapplication;

import android.content.Context;
import android.database.Cursor;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import java.lang.reflect.*;
import java.sql.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SqlIdentitaetsErgaenzungTest {
    private Context context;private String name;private LokalerInventurSpeicher db;
    @Before public void start() {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();name="ident_ergaenzung_"+UUID.randomUUID()+".db";
        db=new LokalerInventurSpeicher(context,name);
        db.initialisiereZaehlerlisten(Collections.singletonList(new ZaehlerlisteDto(99001,"Test","99001","Test","11.11.2026","",false,false,"","","WO")));
        db.initialisiereArtikel(99001,Arrays.asList(new ArtikelDto(1,"TEST-A","Test","L1",3,1,92),new ArtikelDto(2,"TEST-B","Test","L1",3,1,0)));
        List<Artikel> a=db.ladeArtikel(99001);a.get(0).setZaehlMenge(920000);a.get(0).setAnmerkung("Notiz erhalten");
        db.speichereZaehlmengen(99001,"Zaehler","Schreiber",a);
    }
    @After public void stop() {db.close();context.deleteDatabase(name);}
    private String daten(String sql) {
        StringBuilder b=new StringBuilder();try(Cursor c=db.getReadableDatabase().rawQuery(sql,null)) {
            while(c.moveToNext()) {for(int i=0;i<c.getColumnCount();i++)b.append(c.isNull(i)?"<NULL>":c.getString(i)).append('|');b.append('\n');}
        }return b.toString();
    }
    private String geschuetzt() {
        return daten("SELECT zaehl_menge,anmerkung,lokale_revision,menge_lokal_geaendert,notiz_lokal_geaendert,sync_unklar,bestaetigter_serverstand FROM artikel ORDER BY pos_nr")
                +daten("SELECT * FROM zaehlerliste")+daten("SELECT * FROM sync_warteschlange")+daten("SELECT * FROM einzel_sync_auftrag");
    }
    private SqlServerMengenSchreibversuch.Ausgangsstand server() throws Exception {
        return server(1,7);
    }
    private SqlServerMengenSchreibversuch.Ausgangsstand server(int pos,Integer menge) throws Exception {
        return server(99001,pos,menge);
    }
    private SqlServerMengenSchreibversuch.Ausgangsstand server(int blatt,int pos,Integer menge) throws Exception {
        final int[] index={0};
        ResultSet r=proxy(ResultSet.class,(p,m,a)->{
            switch(m.getName()) {
                case "next":return ++index[0]==1;
                case "getLong":return a[0].equals("PositionsId")?(blatt==99001?700L:10000L)+pos:(blatt==99001?90010L:20000L)+pos;
                case "getInt":return a[0].equals("BlattNr")?blatt:a[0].equals("PosNr")?pos:1;
                case "getString":return pos==1?"TEST-A":"TEST-B";
                case "getTimestamp":return Timestamp.valueOf("2026-11-11 00:00:00");
                case "getBigDecimal":return menge==null?null:java.math.BigDecimal.valueOf(menge);
                case "wasNull":return false;
            }return null;
        });
        PreparedStatement s=proxy(PreparedStatement.class,(p,m,a)->m.getName().equals("executeQuery")?r:null);
        Connection c=proxy(Connection.class,(p,m,a)->{
            if(m.getName().equals("getCatalog"))return "DatenWHF";
            if(m.getName().equals("getAutoCommit"))return true;
            if(m.getName().equals("prepareStatement"))return s;return null;
        });
        return new SqlServerMengenSchreibversuch(()->c,true).lese(blatt,pos);
    }
    private SqlIdentitaetsZuordnung.Vorschlag vorschlag() throws Exception {
        return SqlIdentitaetsZuordnung.pruefe(db.ladeLokaleIdentitaetsposition(99001,1),Collections.singletonList(new SqlPosition(701L,99001,1,"TEST-A")),server());
    }
    @SuppressWarnings("unchecked") private static <T>T proxy(Class<T> t,InvocationHandler h) {return (T)Proxy.newProxyInstance(t.getClassLoader(),new Class<?>[]{t},h);}
    @Test public void nurIdentitaetenWerdenErgaenzt() throws Exception {
        String vorher=geschuetzt();db.bestaetigeSqlIdentitaet(vorschlag(),false);
        assertEquals(vorher,geschuetzt());assertEquals("701|99001|",daten("SELECT sql_positions_id,quell_blattnummer FROM artikel WHERE pos_nr=1").trim());
        assertEquals("90011|0|",daten("SELECT ergebnis_id,durchgang_bestaetigt FROM sql_identitaetszuordnung").trim());
        assertTrue(db.hatUnbestaetigteLokaleAenderungen());
    }
    @Test public void ungeklaerterDurchgangHatNurVorschau() throws Exception {
        SqlIdentitaetsZuordnung.Vorschlag v=vorschlag();db.bestaetigeSqlIdentitaet(v,false);
        assertEquals(Integer.valueOf(920000),db.ladeLokaleVersuchsVorschau(v.server));
        assertFalse(db.istSqlDurchgangBestaetigt(v.server));
        try {db.ladeLokaleVersuchsmenge(v.server);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void ausdruecklicherDurchgangErzeugtKeineMengenquittung() throws Exception {
        SqlIdentitaetsZuordnung.Vorschlag v=vorschlag();String vorher=geschuetzt();db.bestaetigeSqlIdentitaet(v,true);
        assertTrue(db.istSqlDurchgangBestaetigt(v.server));assertEquals(Integer.valueOf(920000),db.ladeLokaleVersuchsmenge(v.server));
        assertEquals(vorher,geschuetzt());
    }
    @Test public void fehlerRolltIdentitaetenUndErgebniszuordnungZurueck() throws Exception {
        SqlIdentitaetsZuordnung.Vorschlag v=vorschlag();String vorher=geschuetzt();
        db.getWritableDatabase().execSQL("CREATE TRIGGER test_ident_rollback AFTER UPDATE OF sql_positions_id ON artikel BEGIN SELECT RAISE(ABORT,'Testfehler'); END");
        try {db.bestaetigeSqlIdentitaet(v,true);fail();}catch(android.database.SQLException expected){}
        assertEquals(vorher,geschuetzt());assertEquals("<NULL>|<NULL>|",daten("SELECT sql_positions_id,quell_blattnummer FROM artikel WHERE pos_nr=1").trim());
        assertEquals("0|",daten("SELECT COUNT(*) FROM sql_identitaetszuordnung").trim());
    }
    @Test public void neuereLokaleRevisionVerhindertAlteBestaetigung() throws Exception {
        SqlIdentitaetsZuordnung.Vorschlag v=vorschlag();List<Artikel> a=db.ladeArtikel(99001);a.get(0).setZaehlMenge(8);db.speichereZaehlmengen(99001,"Zaehler","Schreiber",a);
        String vorher=geschuetzt();try {db.bestaetigeSqlIdentitaet(v,true);fail();}catch(IllegalArgumentException expected){}
        assertEquals(vorher,geschuetzt());assertEquals("0|",daten("SELECT COUNT(*) FROM sql_identitaetszuordnung").trim());
    }
    @Test public void bereitsAndersZugeordneteSqlIdWirdAbgelehnt() throws Exception {
        db.getWritableDatabase().execSQL("UPDATE artikel SET sql_positions_id='701' WHERE pos_nr=2");
        SqlIdentitaetsZuordnung.Vorschlag v=vorschlag();String vorher=geschuetzt();
        try {db.bestaetigeSqlIdentitaet(v,true);fail();}catch(IllegalArgumentException expected){}assertEquals(vorher,geschuetzt());
    }
    @Test public void migrationVonZwoelfErgaenztKeineIdentitaetenAutomatisch() {
        String vorher=geschuetzt();db.getWritableDatabase().execSQL("DROP TABLE sql_identitaetszuordnung");db.getWritableDatabase().setVersion(12);db.close();
        db=new LokalerInventurSpeicher(context,name);assertEquals(13,db.getReadableDatabase().getVersion());assertEquals(vorher,geschuetzt());
        assertEquals("0|",daten("SELECT COUNT(*) FROM sql_identitaetszuordnung").trim());
    }
    @Test public void ausdruecklicherWiderrufAendertNurDurchgangsfreigabe() throws Exception {
        db.bestaetigeSqlIdentitaet(vorschlag(),true);String vorher=geschuetzt();
        SqlIdentitaetsZuordnung.Vorschlag frisch=vorschlag();db.bestaetigeSqlIdentitaet(frisch,false);
        assertFalse(db.istSqlDurchgangBestaetigt(frisch.server));assertEquals(vorher,geschuetzt());
        assertEquals("701|99001|",daten("SELECT sql_positions_id,quell_blattnummer FROM artikel WHERE pos_nr=1").trim());
        assertEquals("90011|0|",daten("SELECT ergebnis_id,durchgang_bestaetigt FROM sql_identitaetszuordnung").trim());
    }
    @Test public void neuerLokalerStichtagVerwendetKeineAlteDurchgangsfreigabe() throws Exception {
        SqlIdentitaetsZuordnung.Vorschlag v=vorschlag();db.bestaetigeSqlIdentitaet(v,true);
        db.getWritableDatabase().execSQL("UPDATE zaehlerliste SET stichtag='12.11.2026' WHERE id=99001");
        assertFalse(db.istSqlDurchgangBestaetigt(v.server));
        try {db.ladeLokaleVersuchsmenge(v.server);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void wiederholtesUnveraendertesSpeichernErhaeltWarteschlangeExakt() throws Exception {
        db.bestaetigeSqlIdentitaet(vorschlag(),false);
        db.getWritableDatabase().execSQL("UPDATE sync_warteschlange SET geaendert_am='123456' WHERE zaehlerlisten_id=99001");
        String vorher=geschuetzt();
        db.speichereZaehlmengen(99001,"Zaehler","Schreiber",db.ladeArtikel(99001));
        assertEquals(vorher,geschuetzt());
    }

    private final class EinmalServer implements EinzelpositionsSynchronisierung.Server {
        EinzelpositionsSynchronisierung.Stand stand;int versuche,updates,pos,blatt;
        boolean vorCommit,nachCommit,leseFehler;Runnable waehrend;
        EinmalServer() throws Exception {this(1,7);}
        EinmalServer(int pos,Integer menge) throws Exception {this(99001,pos,menge);}
        EinmalServer(int blatt,int pos,Integer menge) throws Exception {this.blatt=blatt;this.pos=pos;stand=SqlServerMengenSchreibversuch.alsMengenstand(server(blatt,pos,menge));}
        public boolean istIsolierterTestbetrieb(){return false;}
        public boolean istFreigegebenerEinmalbetrieb(){return true;}
        public boolean hatDauerhafteAuftragsquittung(){return false;}
        public EinzelpositionsSynchronisierung.Quittung sucheAuftrag(String id){throw new AssertionError("Keine Serverauftragssuche erlaubt.");}
        public EinzelpositionsSynchronisierung.Quittung verarbeite(EinzelpositionsSynchronisierung.Auftrag a)throws java.io.IOException {
            versuche++;
            if(vorCommit)throw new java.io.IOException("Isolierter Abbruch vor Commit");
            if(!stand.equals(a.erwartet))return new EinzelpositionsSynchronisierung.Quittung(a,null,true);
            if(!java.util.Objects.equals(stand.menge,a.menge))updates++;
            try{stand=SqlServerMengenSchreibversuch.alsMengenstand(server(blatt,pos,a.menge));}catch(Exception e){throw new java.io.IOException("Teststand fehlt");}
            if(waehrend!=null)waehrend.run();
            if(nachCommit)throw new java.io.IOException("Isolierter Antwortverlust nach Commit");
            return new EinzelpositionsSynchronisierung.Quittung(a,stand,false);
        }
    }
    private void vorbereiten(EinmalServer s)throws Exception {
        SqlIdentitaetsZuordnung.Lokal l=db.ladeLokaleIdentitaetsposition(s.blatt,s.pos);
        db.legeEinfachenMengenauftragAn(SqlServerMengenSchreibversuch.ausMengenstand(s.stand),l.fingerprint,l.menge);
    }
    private EinzelpositionsSynchronisierung.Status senden(EinmalServer s) {return new EinzelpositionsSynchronisierung(db,s).synchronisiere(s.blatt,s.pos);}
    private void lokaleMenge(Integer menge,String notiz) {
        List<Artikel> a=db.ladeArtikel(99001);a.get(0).setZaehlMenge(menge);a.get(0).setAnmerkung(notiz);db.speichereZaehlmengen(99001,"Zaehler","Schreiber",a);
    }
    @Test public void einfacherAbgleichQuittiertMengenrevisionAberKeineNotizOderKopf()throws Exception {
        db.bestaetigeSqlIdentitaet(vorschlag(),true);EinmalServer s=new EinmalServer();
        String revision=daten("SELECT lokale_revision FROM artikel WHERE pos_nr=1");vorbereiten(s);
        assertEquals(EinzelpositionsSynchronisierung.Status.BESTAETIGT,senden(s));
        assertEquals("0|1|",daten("SELECT menge_lokal_geaendert,notiz_lokal_geaendert FROM artikel WHERE pos_nr=1").trim());
        assertEquals(revision,daten("SELECT lokale_revision FROM artikel WHERE pos_nr=1"));assertEquals("Notiz erhalten",db.ladeArtikel(99001).get(0).getAnmerkung());
        assertTrue(db.hatUnbestaetigteLokaleAenderungen());assertTrue(db.hatOffeneSynchronisierungFuerZaehlerliste(99001));assertTrue(db.hatEinfachenMengenNachweis(99001));
        assertEquals(Integer.valueOf(920000),db.ladeArtikel(99001).get(0).getZaehlMenge());
    }
    @Test public void bestaetigteMengeUndHistorischeRevisionBlockierenAlleinNicht()throws Exception {
        // Isolierter Ausgangszustand ohne weitere offene Aufgaben.
        db.getWritableDatabase().execSQL("UPDATE artikel SET anmerkung='',notiz_lokal_geaendert=0");
        db.getWritableDatabase().execSQL("UPDATE zaehlerliste SET kopf_lokal_geaendert=0,abschluss_lokal_geaendert=0");
        db.bestaetigeSqlIdentitaet(vorschlag(),true);EinmalServer s=new EinmalServer();vorbereiten(s);
        assertEquals(EinzelpositionsSynchronisierung.Status.BESTAETIGT,senden(s));assertFalse(db.hatUnbestaetigteLokaleAenderungen());
        assertEquals("1|",daten("SELECT lokale_revision FROM artikel WHERE pos_nr=1").trim());
        assertFalse(db.hatOffeneSynchronisierung());
    }
    @Test public void ungeklärterDurchgangErzeugtKeinenSendeauftrag()throws Exception {
        db.bestaetigeSqlIdentitaet(vorschlag(),false);EinmalServer s=new EinmalServer();
        try{vorbereiten(s);fail();}catch(IllegalArgumentException expected){}
        assertEquals("0|",daten("SELECT COUNT(*) FROM einzel_sync_auftrag").trim());assertEquals(0,s.versuche);assertFalse(db.hatEinfachenMengenNachweis(99001));
    }
    @Test public void einfacheNullUndNullmengeWerdenGetrenntQuittiert()throws Exception {
        db.bestaetigeSqlIdentitaet(vorschlag(),true);EinmalServer s=new EinmalServer();
        lokaleMenge(0,"Notiz erhalten");vorbereiten(s);assertEquals(EinzelpositionsSynchronisierung.Status.BESTAETIGT,senden(s));assertEquals(Integer.valueOf(0),s.stand.menge);
        lokaleMenge(null,"Notiz erhalten");vorbereiten(s);assertEquals(EinzelpositionsSynchronisierung.Status.BESTAETIGT,senden(s));assertNull(s.stand.menge);assertNull(db.ladeArtikel(99001).get(0).getZaehlMenge());
    }
    @Test public void unklarerEinfacherCommitBleibtNachNeustartOhneWiederholungOffen()throws Exception {
        db.bestaetigeSqlIdentitaet(vorschlag(),true);EinmalServer s=new EinmalServer();vorbereiten(s);s.nachCommit=true;
        assertEquals(EinzelpositionsSynchronisierung.Status.AUSSTEHEND,senden(s));db.close();db=new LokalerInventurSpeicher(context,name);
        assertEquals(EinzelpositionsSynchronisierung.Status.AUSSTEHEND,senden(s));assertEquals(1,s.versuche);assertEquals(1,s.updates);
        assertEquals("1|",daten("SELECT menge_lokal_geaendert FROM artikel WHERE pos_nr=1").trim());assertFalse(db.hatEinfachenMengenNachweis(99001));
    }
    @Test public void neuerServerwertVorErstemUpdateWirdNichtUeberschrieben()throws Exception {
        db.bestaetigeSqlIdentitaet(vorschlag(),true);EinmalServer s=new EinmalServer();vorbereiten(s);
        s.stand=SqlServerMengenSchreibversuch.alsMengenstand(server(1,8));
        assertEquals(EinzelpositionsSynchronisierung.Status.KONFLIKT,senden(s));assertEquals(0,s.updates);
        assertEquals("KONFLIKT|",daten("SELECT status FROM einzel_sync_auftrag").trim());assertTrue(db.hatUnbestaetigteLokaleAenderungen());
    }
    @Test public void neuereLokaleRevisionWaehrendSqlBleibtOffen()throws Exception {
        db.bestaetigeSqlIdentitaet(vorschlag(),true);EinmalServer s=new EinmalServer();vorbereiten(s);s.waehrend=()->lokaleMenge(8,"Neuere Notiz");
        assertEquals(EinzelpositionsSynchronisierung.Status.BESTAETIGT,senden(s));
        assertEquals(Integer.valueOf(8),db.ladeArtikel(99001).get(0).getZaehlMenge());assertEquals("Neuere Notiz",db.ladeArtikel(99001).get(0).getAnmerkung());
        assertEquals("1|2|",daten("SELECT menge_lokal_geaendert,lokale_revision FROM artikel WHERE pos_nr=1").trim());
    }
    @Test public void einfacherSQLiteQuittierungsfehlerRolltZurueckUndSperrtWiederholung()throws Exception {
        db.bestaetigeSqlIdentitaet(vorschlag(),true);EinmalServer s=new EinmalServer();vorbereiten(s);
        db.getWritableDatabase().execSQL("CREATE TRIGGER test_einfach_quittung BEFORE UPDATE ON einzel_sync_auftrag WHEN NEW.status='BESTAETIGT' BEGIN SELECT RAISE(ABORT,'Testfehler'); END");
        assertEquals(EinzelpositionsSynchronisierung.Status.AUSSTEHEND,senden(s));
        db.getWritableDatabase().execSQL("DROP TRIGGER test_einfach_quittung");
        assertEquals(EinzelpositionsSynchronisierung.Status.AUSSTEHEND,senden(s));assertEquals(1,s.versuche);
        assertEquals("<NULL>|1|",daten("SELECT bestaetigter_serverstand,menge_lokal_geaendert FROM artikel WHERE pos_nr=1").trim());
    }
    @Test public void zuletztBestaetigterServerwertWirdBeiSpaeteremKonfliktErhalten()throws Exception {
        db.bestaetigeSqlIdentitaet(vorschlag(),true);EinmalServer s=new EinmalServer();vorbereiten(s);assertEquals(EinzelpositionsSynchronisierung.Status.BESTAETIGT,senden(s));
        String basis=daten("SELECT bestaetigter_serverstand FROM artikel WHERE pos_nr=1");lokaleMenge(8,"Notiz erhalten");
        s.stand=SqlServerMengenSchreibversuch.alsMengenstand(server(1,9));
        try{db.pruefeEinfachenMengenstand(SqlServerMengenSchreibversuch.ausMengenstand(s.stand));fail();}catch(IllegalArgumentException expected){}
        assertEquals(basis,daten("SELECT bestaetigter_serverstand FROM artikel WHERE pos_nr=1"));assertEquals(1,s.updates);
    }
    @Test public void mehrereGepruefteMengenNutzenDenselbenEinzelablauf()throws Exception {
        db.bestaetigeSqlIdentitaet(vorschlag(),true);
        // Zweite eindeutig zugeordnete synthetische Position, keine Zuordnung echter Altbestaende.
        db.getWritableDatabase().execSQL("UPDATE artikel SET sql_positions_id='702',quell_blattnummer='99001',quell_stichtag='2026-11-11 00:00:00.0' WHERE pos_nr=2");
        db.getWritableDatabase().execSQL("INSERT INTO sql_identitaetszuordnung VALUES(99001,2,702,90012,'TEST-B','2026-11-11 00:00:00.0',1,'2026-11-11 00:00:00.0','11.11.2026',1)");
        List<Artikel> a=db.ladeArtikel(99001);a.get(1).setZaehlMenge(4);db.speichereZaehlmengen(99001,"Zaehler","Schreiber",a);
        assertEquals(Arrays.asList(1,2),db.ladeGeaenderteMengenpositionen(99001));assertFalse(db.hatEinfachenMengenNachweis(99001));
        EinmalServer erster=new EinmalServer();vorbereiten(erster);assertEquals(EinzelpositionsSynchronisierung.Status.BESTAETIGT,senden(erster));assertTrue(db.hatEinfachenMengenNachweis(99001));
        EinmalServer zweiter=new EinmalServer(2,0);vorbereiten(zweiter);assertEquals(EinzelpositionsSynchronisierung.Status.BESTAETIGT,senden(zweiter));
        assertTrue(db.ladeGeaenderteMengenpositionen(99001).isEmpty());assertEquals(1,erster.updates);assertEquals(1,zweiter.updates);assertTrue(db.hatUnbestaetigteLokaleAenderungen());
    }
    @Test public void schonGleicheMengeWirdOhneUpdateQuittiertAberAktiviertKeinenMehrmengentest()throws Exception {
        db.bestaetigeSqlIdentitaet(vorschlag(),true);lokaleMenge(7,"Notiz erhalten");EinmalServer s=new EinmalServer();vorbereiten(s);
        assertEquals(EinzelpositionsSynchronisierung.Status.BESTAETIGT,senden(s));assertEquals(0,s.updates);
        assertEquals("0|",daten("SELECT menge_lokal_geaendert FROM artikel WHERE pos_nr=1").trim());assertFalse(db.hatEinfachenMengenNachweis(99001));
    }

    private ZaehlerlisteDto downloadKopf(String stichtag) {
        return new ZaehlerlisteDto(99005,"Downloadtest","99005","Test",stichtag,"",false,false,"Zaehler","Schreiber","WO");
    }
    private ArtikelDto downloadArtikel(int pos,Integer menge)throws Exception {
        return new ArtikelDto(pos,pos==1?"TEST-A":"TEST-B","Test","L1",3,null,1,menge,"",10000L+pos,99005)
                .mitSqlErgebnis(server(99005,pos,menge));
    }
    @Test public void ersterDownloadSpeichertAlleIdentitaetenUndServerausgangswert()throws Exception {
        db.speichereErstdownload(downloadKopf("11.11.2026"),Arrays.asList(downloadArtikel(1,7),downloadArtikel(2,null)));
        assertEquals("10001|99005|2026-11-11 00:00:00.0|0|0|0|",daten("SELECT sql_positions_id,quell_blattnummer,quell_stichtag,sync_unklar,menge_lokal_geaendert,lokale_revision FROM artikel WHERE zaehlerlisten_id=99005 AND pos_nr=1").trim());
        assertEquals("20001|1|",daten("SELECT ergebnis_id,durchgang_bestaetigt FROM sql_identitaetszuordnung WHERE blatt=99005 AND pos=1").trim());
        assertEquals(Integer.valueOf(7),db.ladeArtikel(99005).get(0).getZaehlMenge());assertNull(db.ladeArtikel(99005).get(1).getZaehlMenge());
        assertFalse(db.hatOffeneSynchronisierungFuerZaehlerliste(99005));
    }
    @Test public void erneuterDownloadUeberschreibtKeineBestehendenAltpositionen()throws Exception {
        String vorher=geschuetzt();ArtikelDto neu=new ArtikelDto(1,"TEST-A","Test","L1",3,null,1,7,"",701L,99001).mitSqlErgebnis(server());
        db.speichereErstdownload(new ZaehlerlisteDto(99001,"Neuer Kopf","99001","Anderer Bereich","11.11.2026","",false,false,"","","WO"),Collections.singletonList(neu));
        assertEquals(vorher,geschuetzt());assertNull(db.ladeArtikel(99001).get(0).getSqlPositionsId());
        assertEquals("0|",daten("SELECT COUNT(*) FROM sql_identitaetszuordnung").trim());
    }
    @Test public void fehlendesErgebnisBleibtOhneAutomatischeSchreibfreigabe() {
        ArtikelDto a=new ArtikelDto(1,"TEST-A","Test","L1",3,null,1,7,"",10001L,99005);
        db.speichereErstdownload(downloadKopf("11.11.2026"),Collections.singletonList(a));
        assertEquals("1|<NULL>|",daten("SELECT sync_unklar,bestaetigter_serverstand FROM artikel WHERE zaehlerlisten_id=99005").trim());
        assertEquals("0|",daten("SELECT COUNT(*) FROM sql_identitaetszuordnung WHERE blatt=99005").trim());
    }
    @Test public void widerspruechlicherStichtagErteiltKeineFreigabeBeimDownload()throws Exception {
        db.speichereErstdownload(downloadKopf("12.11.2026"),Collections.singletonList(downloadArtikel(1,7)));
        assertEquals("1|<NULL>|",daten("SELECT sync_unklar,bestaetigter_serverstand FROM artikel WHERE zaehlerlisten_id=99005").trim());
        assertEquals("20001|0|",daten("SELECT ergebnis_id,durchgang_bestaetigt FROM sql_identitaetszuordnung WHERE blatt=99005").trim());
    }
    @Test public void fehlerhafterErstdownloadRolltAuchNeuenKopfZurueck()throws Exception {
        ArtikelDto a=downloadArtikel(1,7);
        try{db.speichereErstdownload(downloadKopf("11.11.2026"),Arrays.asList(a,a));fail();}catch(IllegalArgumentException expected){}
        assertNull(db.ladeZaehlerlisteFuerId(99005));assertTrue(db.ladeArtikel(99005).isEmpty());
    }
    @Test public void frischerDownloadAenderungUndQuittierungBrauchenKeineManuelleIdentitaetsfreigabe()throws Exception {
        db.speichereErstdownload(downloadKopf("11.11.2026"),Collections.singletonList(downloadArtikel(1,7)));
        List<Artikel> a=db.ladeArtikel(99005);a.get(0).setZaehlMenge(0);db.speichereZaehlmengen(99005,"Zaehler","Schreiber",a);
        assertEquals("1|1|",daten("SELECT menge_lokal_geaendert,lokale_revision FROM artikel WHERE zaehlerlisten_id=99005").trim());
        EinmalServer s=new EinmalServer(99005,1,7);vorbereiten(s);assertEquals(EinzelpositionsSynchronisierung.Status.BESTAETIGT,senden(s));
        assertEquals("0|1|",daten("SELECT menge_lokal_geaendert,lokale_revision FROM artikel WHERE zaehlerlisten_id=99005").trim());
        assertEquals(Integer.valueOf(0),s.stand.menge);assertFalse(db.hatOffeneSynchronisierungFuerZaehlerliste(99005));assertTrue(db.hatOffeneSynchronisierungFuerZaehlerliste(99001));
    }
    @Test public void repositoryLaedtNeuesBlattNurEinmalUndLaesstAltbestandErhalten()throws Exception {
        ArtikelDto a=downloadArtikel(1,7);final int[] downloads={0};
        ApiInventurRemoteDataSource quelle=new ApiInventurRemoteDataSource(db) {
            @Override public List<ZaehlerlisteDto> ladeZaehlerlisten(){return Collections.singletonList(downloadKopf("11.11.2026"));}
            @Override public List<ArtikelDto> ladeArtikel(int blatt){downloads[0]++;return Collections.singletonList(a);}
        };
        ArtikelRepository repo=new ArtikelRepository(quelle,db);String alt=daten("SELECT * FROM artikel WHERE zaehlerlisten_id=99001 ORDER BY pos_nr");
        repo.ladeSqlBlattFallsFehlt(99005);repo.ladeSqlBlattFallsFehlt(99005);
        assertEquals(1,downloads[0]);assertEquals(1,db.ladeArtikel(99005).size());
        assertEquals(alt,daten("SELECT * FROM artikel WHERE zaehlerlisten_id=99001 ORDER BY pos_nr"));
    }
    private ArtikelRepository austrittsRepository(EinmalServer... server) {
        Map<Integer,EinmalServer> positionen=new HashMap<>();
        for(EinmalServer s:server)positionen.put(s.pos,s);
        return new ArtikelRepository(new ApiInventurRemoteDataSource(db),db) {
            @Override public boolean istSqlMengenabgleichVerfuegbar(){return true;}
            @Override public SqlServerMengenSchreibversuch.Ausgangsstand leseKontrolliertenMengenstand(int blatt,int pos) throws SQLException {
                EinmalServer s=positionen.get(pos);
                if(s.leseFehler)throw new SQLException("Isolierter Verbindungsfehler","08001");
                return SqlServerMengenSchreibversuch.ausMengenstand(s.stand);
            }
            @Override public MengenPlan bereiteEinfachenMengenabgleichVor(SqlServerMengenSchreibversuch.Ausgangsstand stand) {
                db.pruefeEinfachenMengenstand(stand);
                SqlIdentitaetsZuordnung.Lokal lokal=db.ladeLokaleIdentitaetsposition(stand.blatt,stand.position);
                return new MengenPlan(stand,lokal.menge,lokal.fingerprint);
            }
            @Override public MengenAntwort synchronisiereEinzelmengeEinfach(MengenPlan plan) {
                EinmalServer s=positionen.get(plan.server.position);int vorher=s.updates;
                db.legeEinfachenMengenauftragAn(plan.server,plan.fingerprint,plan.menge);
                EinzelpositionsSynchronisierung.Status status=senden(s);
                return new MengenAntwort(status,"Isolierter Mengenserver: "+status,s.updates-vorher);
            }
        };
    }
    private void aendereDownloadMengen(boolean zweite) throws Exception {
        db.speichereErstdownload(downloadKopf("11.11.2026"),zweite
                ?Arrays.asList(downloadArtikel(1,7),downloadArtikel(2,null))
                :Collections.singletonList(downloadArtikel(1,7)));
        List<Artikel> artikel=db.ladeArtikel(99005);artikel.get(0).setZaehlMenge(0);
        if(zweite)artikel.get(1).setZaehlMenge(5);
        db.speichereZaehlmengen(99005,"Zaehler","Schreiber",artikel);
    }
    @Test public void austrittOffenerListeQuittiertMehrereMengenOhneAbschluss() throws Exception {
        aendereDownloadMengen(true);
        EinmalServer erster=new EinmalServer(99005,1,7),zweiter=new EinmalServer(99005,2,null);
        ArtikelRepository repo=austrittsRepository(erster,zweiter);
        assertTrue(repo.synchronisiereBeimVerlassen(99005,false).istErfolgreich());
        assertFalse(db.ladeZaehlerlisteFuerId(99005).istAbgeschlossen());
        assertFalse(db.hatOffeneSynchronisierungFuerZaehlerliste(99005));
        assertEquals(1,erster.updates);assertEquals(1,zweiter.updates);
        assertTrue(repo.synchronisiereBeimVerlassen(99005,false).istErfolgreich());
        assertEquals(1,erster.versuche);assertEquals(1,zweiter.versuche);
        assertTrue(db.hatOffeneSynchronisierungFuerZaehlerliste(99001));
    }
    @Test public void austrittsAbschlussOhneSchreibwegQuittiertNurDieMenge() throws Exception {
        aendereDownloadMengen(false);EinmalServer s=new EinmalServer(99005,1,7);
        ArtikelRepository repo=austrittsRepository(s);
        ZaehlmengenSpeicherAntwort antwort=repo.synchronisiereBeimVerlassen(99005,true);
        assertTrue(antwort.istErfolgreich());assertTrue(antwort.getMeldung().contains("Abschluss bleibt nur lokal"));
        assertTrue(db.ladeZaehlerlisteFuerId(99005).istAbgeschlossen());
        assertEquals("0|1|",daten("SELECT menge_lokal_geaendert,lokale_revision FROM artikel WHERE zaehlerlisten_id=99005").trim());
        assertEquals("1|",daten("SELECT abschluss_lokal_geaendert FROM zaehlerliste WHERE id=99005").trim());
        assertTrue(repo.synchronisiereBeimVerlassen(99005,true).istErfolgreich());
        assertEquals(1,s.versuche);assertEquals(1,s.updates);
    }
    @Test public void notizenUndKopfBleibenBeimAustrittOhneSchreibwegOffen() throws Exception {
        aendereDownloadMengen(false);List<Artikel> artikel=db.ladeArtikel(99005);
        artikel.get(0).setAnmerkung("Nicht quittierte Notiz");
        db.speichereZaehlmengen(99005,"Zaehler","Anderer Schreiber",artikel);
        EinmalServer s=new EinmalServer(99005,1,7);
        ZaehlmengenSpeicherAntwort antwort=austrittsRepository(s).synchronisiereBeimVerlassen(99005,false);
        assertTrue(antwort.istErfolgreich());assertTrue(antwort.getMeldung().contains("Notizen bleiben offen"));
        assertTrue(antwort.getMeldung().contains("Kopfdaten bleiben offen"));
        assertFalse(db.ladeZaehlerlisteFuerId(99005).istAbgeschlossen());
        assertEquals("0|1|",daten("SELECT menge_lokal_geaendert,notiz_lokal_geaendert FROM artikel WHERE zaehlerlisten_id=99005").trim());
        assertEquals("Nicht quittierte Notiz",db.ladeArtikel(99005).get(0).getAnmerkung());
    }
    @Test public void leseFehlerBlockiertAndereMengeNichtUndErlaubtSpaeterenVersuch() throws Exception {
        aendereDownloadMengen(true);EinmalServer erster=new EinmalServer(99005,1,7),zweiter=new EinmalServer(99005,2,null);
        erster.leseFehler=true;ArtikelRepository repo=austrittsRepository(erster,zweiter);
        ZaehlmengenSpeicherAntwort bericht=repo.synchronisiereBeimVerlassen(99005,false);
        assertFalse(bericht.istErfolgreich());assertTrue(bericht.istWiederholbar());
        assertEquals(1,bericht.getUebernommen());assertEquals(2,bericht.getGesamt());
        assertTrue(bericht.getBenutzerMeldung().contains("Position 1"));
        assertTrue(bericht.getBenutzerMeldung().contains("Verbindung"));
        assertTrue(bericht.getMeldung().contains("08001"));
        assertEquals(0,erster.versuche);assertEquals(1,zweiter.versuche);
        erster.leseFehler=false;
        assertTrue(repo.synchronisiereBeimVerlassen(99005,false).istErfolgreich());
        assertEquals(1,erster.versuche);assertEquals(1,zweiter.versuche);
    }
    @Test public void unklarerCommitWirdBeimErneutenAustrittNichtWiederholt() throws Exception {
        aendereDownloadMengen(false);EinmalServer s=new EinmalServer(99005,1,7);s.nachCommit=true;
        ArtikelRepository repo=austrittsRepository(s);
        ZaehlmengenSpeicherAntwort bericht=repo.synchronisiereBeimVerlassen(99005,false);
        assertFalse(bericht.istErfolgreich());assertFalse(bericht.istWiederholbar());
        assertTrue(bericht.getBenutzerMeldung().contains("nicht bestätigt"));
        bericht=repo.synchronisiereBeimVerlassen(99005,false);
        assertFalse(bericht.istErfolgreich());assertFalse(bericht.istWiederholbar());
        assertEquals(0,bericht.getUebernommen());assertEquals(1,bericht.getGesamt());
        assertTrue(bericht.getMeldung().contains("UNKLAR"));
        assertEquals(1,s.versuche);assertEquals(1,s.updates);
        assertEquals("1|",daten("SELECT menge_lokal_geaendert FROM artikel WHERE zaehlerlisten_id=99005").trim());
        assertEquals("UNKLAR|",daten("SELECT status FROM einzel_sync_auftrag WHERE blatt_id=99005").trim());
    }
    @Test public void austrittPrueftKennzeichenAuchOhneWarteschlangeneintrag() throws Exception {
        aendereDownloadMengen(false);db.entferneZaehlerlisteAusSynchronisierung(99005);
        assertTrue(db.hatOffeneSynchronisierungFuerZaehlerliste(99005));
        EinmalServer s=new EinmalServer(99005,1,7);
        assertTrue(austrittsRepository(s).synchronisiereBeimVerlassen(99005,false).istErfolgreich());
        assertFalse(db.hatOffeneSynchronisierungFuerZaehlerliste(99005));
    }
    @Test public void austrittErkenntOffenenEinzelauftragAuchOhneKennzeichen() throws Exception {
        aendereDownloadMengen(false);EinmalServer s=new EinmalServer(99005,1,7);vorbereiten(s);
        db.getWritableDatabase().execSQL("UPDATE artikel SET menge_lokal_geaendert=0 WHERE zaehlerlisten_id=99005");
        db.entferneZaehlerlisteAusSynchronisierung(99005);
        assertTrue(db.hatOffeneSynchronisierungFuerZaehlerliste(99005));
        assertFalse(austrittsRepository(s).synchronisiereBeimVerlassen(99005,false).istErfolgreich());
        assertEquals(0,s.versuche);
        assertTrue(db.hatOffeneMengensynchronisierung(null));
        assertTrue(db.hatOffeneMengensynchronisierung(99005));
        assertFalse(austrittsRepository(s).istHauptmenueRueckkehrErlaubt());
    }

    @Test public void ungeklaerteErstePositionBlockiertFreigegebeneZweiteNicht() throws Exception {
        List<Artikel> artikel=db.ladeArtikel(99001);artikel.get(1).setZaehlMenge(5);
        db.speichereZaehlmengen(99001,"Zaehler","Schreiber",artikel);
        SqlIdentitaetsZuordnung.Vorschlag zweite=SqlIdentitaetsZuordnung.pruefe(
                db.ladeLokaleIdentitaetsposition(99001,2),
                Collections.singletonList(new SqlPosition(702L,99001,2,"TEST-B")),server(2,0));
        db.bestaetigeSqlIdentitaet(zweite,true);
        String vorher=geschuetzt();
        ArtikelRepository repo=planRepository(false);
        ArtikelRepository.MengenListe plan=repo.bereiteGeaenderteMengenVor(99001);
        assertEquals(1,plan.positionen.size());assertEquals(2,plan.positionen.get(0).server.position);
        assertEquals(1,plan.zuordnungen.size());assertEquals(1,plan.zuordnungen.get(0).lokal.position);
        assertFalse(db.istSqlDurchgangBestaetigt(server()));assertEquals(vorher,geschuetzt());
        assertEquals("701|99001|",daten("SELECT sql_positions_id,quell_blattnummer FROM artikel WHERE pos_nr=1").trim());
    }
    @Test public void sqlFehlerEinerPositionBlockiertFreigegebeneZweiteNicht() throws Exception {
        List<Artikel> artikel=db.ladeArtikel(99001);artikel.get(1).setZaehlMenge(5);
        db.speichereZaehlmengen(99001,"Zaehler","Schreiber",artikel);
        db.bestaetigeSqlIdentitaet(SqlIdentitaetsZuordnung.pruefe(db.ladeLokaleIdentitaetsposition(99001,2),
                Collections.singletonList(new SqlPosition(702L,99001,2,"TEST-B")),server(2,0)),true);
        String vorher=geschuetzt();
        ArtikelRepository.MengenListe plan=planRepository(true).bereiteGeaenderteMengenVor(99001);
        assertEquals(1,plan.positionen.size());assertEquals(2,plan.positionen.get(0).server.position);
        assertEquals(1,plan.probleme.size());assertTrue(plan.probleme.get(0).startsWith("Position 1:"));
        assertEquals(vorher,geschuetzt());
    }
    private ArtikelRepository planRepository(boolean ersteFehlerhaft) {
        ApiInventurRemoteDataSource quelle=new ApiInventurRemoteDataSource(db) {
            @Override public List<SqlPosition> ladeSqlPositionen(int blatt) {
                return Arrays.asList(new SqlPosition(701L,99001,1,"TEST-A"),new SqlPosition(702L,99001,2,"TEST-B"));
            }
        };
        return new ArtikelRepository(quelle,db) {
            @Override public SqlServerMengenSchreibversuch.Ausgangsstand leseKontrolliertenMengenstand(int blatt,int pos) throws SQLException {
                if(ersteFehlerhaft && pos==1)throw new SQLException("Synthetischer Lesefehler","23000");
                try{return server(pos,pos==1?7:0);}catch(Exception e){throw new SQLException("Teststand fehlt",e);}
            }
            @Override public MengenPlan bereiteEinfachenMengenabgleichVor(SqlServerMengenSchreibversuch.Ausgangsstand stand) {
                db.pruefeEinfachenMengenstand(stand);
                SqlIdentitaetsZuordnung.Lokal lokal=db.ladeLokaleIdentitaetsposition(stand.blatt,stand.position);
                return new MengenPlan(stand,lokal.menge,lokal.fingerprint);
            }
        };
    }
    @Test public void zweitePositionErgaenztNurTechnischeIdentitaet() throws Exception {
        List<Artikel> artikel=db.ladeArtikel(99001);artikel.get(1).setZaehlMenge(5);
        db.speichereZaehlmengen(99001,"Zaehler","Schreiber",artikel);
        String vorher=geschuetzt();
        SqlIdentitaetsZuordnung.Vorschlag v=SqlIdentitaetsZuordnung.pruefe(
                db.ladeLokaleIdentitaetsposition(99001,2),
                Collections.singletonList(new SqlPosition(702L,99001,2,"TEST-B")),server(2,0));
        db.bestaetigeSqlIdentitaet(v,false);
        assertEquals(vorher,geschuetzt());assertFalse(db.istSqlDurchgangBestaetigt(v.server));
        try{db.pruefeEinfachenMengenstand(v.server);fail();}catch(IllegalArgumentException expected){}
        SqlIdentitaetsZuordnung.Vorschlag frisch=SqlIdentitaetsZuordnung.pruefe(
                db.ladeLokaleIdentitaetsposition(99001,2),Collections.singletonList(new SqlPosition(702L,99001,2,"TEST-B")),server(2,0));
        db.bestaetigeSqlIdentitaet(frisch,true);
        db.pruefeEinfachenMengenstand(frisch.server);
        assertEquals(vorher,geschuetzt());assertTrue(db.istSqlDurchgangBestaetigt(frisch.server));
        assertFalse(db.istSqlDurchgangBestaetigt(server()));
    }
    @Test public void zusammenfassungZaehltTeilErfolgUndErhaeltOffeneFelder() throws Exception {
        final int[] aufrufe={0};
        ArtikelRepository repo=new ArtikelRepository(new MockInventurRemoteDataSource(),db) {
            @Override public MengenAntwort synchronisiereEinzelmengeEinfach(MengenPlan plan) {
                aufrufe[0]++;
                return new MengenAntwort(aufrufe[0]==1?EinzelpositionsSynchronisierung.Status.BESTAETIGT:
                        EinzelpositionsSynchronisierung.Status.KONFLIKT,"Testposition "+aufrufe[0],aufrufe[0]==1?1:0);
            }
        };
        List<Artikel> artikel=db.ladeArtikel(99001);artikel.get(1).setZaehlMenge(5);db.speichereZaehlmengen(99001,null,null,artikel);
        String vorher=geschuetzt();
        ArtikelRepository.MengenListe liste=new ArtikelRepository.MengenListe(99001,Arrays.asList(
                new ArtikelRepository.MengenPlan(server(),0,"a"),new ArtikelRepository.MengenPlan(server(2,0),5,"b")),
                Collections.singletonList("Position 3: Zuordnung ungekl\u00e4rt"));
        String text=repo.synchronisiereMengenliste(liste);
        assertEquals(2,aufrufe[0]);assertTrue(text.contains("\u00fcbertragene Mengen: 1"));
        assertTrue(text.contains("Position 3: Zuordnung ungekl\u00e4rt"));assertTrue(text.contains("Hauptmen\u00fc bleibt gesperrt"));
        assertTrue(text.contains("Unbestaetigte Mengen: 2"));assertTrue(text.contains("Unbestaetigte Notizen: 1"));
        assertEquals(vorher,geschuetzt());
    }

    private ArtikelRepository zehnMengen(int erfolge, int[] versuche) throws Exception {
        List<ArtikelDto> artikel=new ArrayList<>();
        for(int pos=1;pos<=10;pos++)artikel.add(downloadArtikel(pos,7));
        db.speichereErstdownload(downloadKopf("11.11.2026"),artikel);
        List<Artikel> lokal=db.ladeArtikel(99005);
        for(Artikel a:lokal)a.setZaehlMenge(0);
        db.speichereZaehlmengen(99005,null,null,lokal);
        return new ArtikelRepository(new MockInventurRemoteDataSource(),db) {
            @Override public boolean istSqlMengenabgleichVerfuegbar(){return true;}
            @Override public MengenListe bereiteGeaenderteMengenVor(int blatt) {
                List<MengenPlan> plaene=new ArrayList<>();
                try {
                    for(int pos:db.ladeGeaenderteMengenpositionen(blatt))plaene.add(new MengenPlan(server(blatt,pos,7),0,"Test"));
                }catch(Exception e){throw new AssertionError(e);}
                if(!plaene.isEmpty())plaene.add(plaene.get(0));
                return new MengenListe(blatt,plaene,Collections.emptyList());
            }
            @Override public MengenAntwort synchronisiereEinzelmengeEinfach(MengenPlan plan) {
                versuche[0]++;
                int pos=plan.server.position;
                if(pos<=erfolge) {
                    // Fixture einer bestaetigten Revision; der echte Einzelablauf ist separat getestet.
                    db.getWritableDatabase().execSQL("UPDATE artikel SET menge_lokal_geaendert=0 WHERE zaehlerlisten_id=99005 AND pos_nr=?",new Object[]{pos});
                    return new MengenAntwort(EinzelpositionsSynchronisierung.Status.BESTAETIGT,"Position "+pos+": Testbestaetigung",1);
                }
                return new MengenAntwort(EinzelpositionsSynchronisierung.Status.KONFLIKT,"Position "+pos+": Testkonflikt",0);
            }
        };
    }
    @Test public void berichtZaehltZehnErfolgeOhneDoppeltePosition() throws Exception {
        int[] versuche={0};ArtikelRepository repo=zehnMengen(10,versuche);
        ZaehlmengenSpeicherAntwort a=repo.synchronisiereBeimVerlassen(99005,false);
        assertTrue(a.istErfolgreich());assertEquals(10,a.getUebernommen());assertEquals(10,a.getGesamt());assertEquals(10,versuche[0]);
        a=repo.synchronisiereBeimVerlassen(99005,false);
        assertEquals(0,a.getGesamt());assertEquals(0,a.getUebernommen());assertEquals(10,versuche[0]);
    }
    @Test public void berichtZaehltTeilErfolgUndNenntJedeOffenePosition() throws Exception {
        int[] versuche={0};ArtikelRepository repo=zehnMengen(5,versuche);
        ZaehlmengenSpeicherAntwort a=repo.synchronisiereBeimVerlassen(99005,false);
        assertFalse(a.istErfolgreich());assertEquals(5,a.getUebernommen());assertEquals(10,a.getGesamt());
        for(int pos=6;pos<=10;pos++)assertTrue(a.getBenutzerMeldung().contains("Position "+pos+" "));
        assertFalse(a.istWiederholbar());assertTrue(a.getBenutzerMeldung().contains("zwischenzeitlich"));
        a=repo.synchronisiereBeimVerlassen(99005,false);
        assertEquals(0,a.getUebernommen());assertEquals(5,a.getGesamt());assertEquals(15,versuche[0]);
    }
    @Test public void berichtZaehltNullErfolgeBeiZehnKonflikten() throws Exception {
        int[] versuche={0};ZaehlmengenSpeicherAntwort a=zehnMengen(0,versuche).synchronisiereBeimVerlassen(99005,false);
        assertFalse(a.istErfolgreich());assertEquals(0,a.getUebernommen());assertEquals(10,a.getGesamt());assertEquals(10,versuche[0]);
    }

}
