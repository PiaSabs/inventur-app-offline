package com.example.myapplication;

import android.content.Context;
import android.database.Cursor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import static com.example.myapplication.EinzelpositionsSynchronisierung.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class EinzelpositionsSynchronisierungTest {
    private Context context;
    private String lokalName, serverName;
    private LokalerInventurSpeicher lokal;
    private IsolierterMengenserver server;

    @Before public void vorbereiten() {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        lokalName="einzel_lokal_"+UUID.randomUUID()+".db";
        serverName="einzel_server_"+UUID.randomUUID()+".db";
        lokal=neuerSpeicher(lokalName,null);
        server=new IsolierterMengenserver(context,serverName);
        server.seed(new Stand(41,1,701,90011,"TEST-A","TEST-RUNDE-A","1",null));
        lokal.merkeBestaetigtenMengenAusgangsstand(server.leseStand());
    }
    @After public void aufraeumen() {
        lokal.close(); server.close(); context.deleteDatabase(lokalName); context.deleteDatabase(serverName);
    }
    private LokalerInventurSpeicher neuerSpeicher(String name, Integer menge) {
        LokalerInventurSpeicher s=new LokalerInventurSpeicher(context,name);
        s.initialisiereZaehlerlisten(Collections.singletonList(new ZaehlerlisteDto(41,"Test","41","Test","",true,false)));
        s.initialisiereArtikel(41,Collections.singletonList(new ArtikelDto(1,"TEST-A","Test","L1",3,null,1,menge,"",701L,41)));
        return s;
    }
    private void aendern(LokalerInventurSpeicher s,Integer menge,String notiz) {
        List<Artikel> artikel=s.ladeArtikel(41); artikel.get(0).setZaehlMenge(menge); artikel.get(0).setAnmerkung(notiz);
        s.speichereZaehlmengen(41,"","",artikel);
    }
    private Status sync() { return new EinzelpositionsSynchronisierung(lokal,server).synchronisiere(41,1); }
    private String feld(String spalte) {
        try(Cursor c=lokal.getReadableDatabase().rawQuery("SELECT "+spalte+" FROM artikel",null)) {
            assertTrue(c.moveToFirst()); return c.isNull(0)?null:c.getString(0);
        }
    }
    private boolean freigegeben() { return !lokal.hatUnbestaetigteLokaleAenderungen(); }

    @Test public void einzelneGeaenderteMengeWirdBestaetigt() {
        aendern(lokal,5,""); String revision=feld("lokale_revision");
        assertFalse(freigegeben()); assertEquals(Status.BESTAETIGT,sync());
        assertEquals(Integer.valueOf(5),server.leseStand().menge);
        assertEquals("0",feld("menge_lokal_geaendert"));
        assertEquals(revision,feld("lokale_revision")); assertTrue(freigegeben());
        assertEquals(1,server.anzahl("ergebnis")); assertEquals(1,server.anzahl("quittung"));
    }
    @Test public void nullmengeIstGezahlteNull() {
        aendern(lokal,0,""); assertEquals(Status.BESTAETIGT,sync());
        assertEquals(Integer.valueOf(0),server.leseStand().menge);
    }
    @Test public void nullBleibtVonNullmengeUnterschieden() {
        aendern(lokal,0,""); assertEquals(Status.BESTAETIGT,sync());
        aendern(lokal,null,""); assertEquals(Status.BESTAETIGT,sync());
        assertNull(server.leseStand().menge); assertNull(lokal.ladeArtikel(41).get(0).getZaehlMenge());
    }
    @Test public void unveraendertePositionErzeugtKeinenAuftrag() {
        assertEquals(Status.NICHTS_ZU_TUN,sync()); assertEquals(0,server.verarbeitungen);
        assertEquals(0,server.anzahl("quittung"));
    }
    @Test public void zweiGeraeteErkennenDenKonflikt() {
        String peerName="einzel_peer_"+UUID.randomUUID()+".db";
        try(LokalerInventurSpeicher peer=neuerSpeicher(peerName,null)) {
            peer.merkeBestaetigtenMengenAusgangsstand(server.leseStand());
            aendern(lokal,5,""); aendern(peer,9,"");
            assertEquals(Status.BESTAETIGT,sync());
            assertEquals(Status.KONFLIKT,new EinzelpositionsSynchronisierung(peer,server).synchronisiere(41,1));
            assertEquals(Integer.valueOf(5),server.leseStand().menge);
            assertEquals(Integer.valueOf(9),peer.ladeArtikel(41).get(0).getZaehlMenge());
            assertTrue(peer.hatUnbestaetigteLokaleAenderungen());
        } finally { context.deleteDatabase(peerName); }
    }
    @Test public void wiederholungErzeugtWederDoppeltesErgebnisNochZweiteAenderung() {
        aendern(lokal,5,""); Auftrag a=lokal.bereiteMengenauftragVor(41,1);
        assertEquals(Status.BESTAETIGT,sync());
        try { assertTrue(a.equals(server.verarbeite(a).auftrag)); } catch(Exception e) { throw new AssertionError(e); }
        assertEquals("2",server.leseStand().token); assertEquals(1,server.anzahl("ergebnis"));
        assertEquals(1,server.anzahl("quittung")); assertEquals(Status.NICHTS_ZU_TUN,sync());
    }
    @Test public void abbruchVorCommitWirdMitDemselbenAuftragWiederholt() {
        aendern(lokal,0,""); Auftrag a=lokal.bereiteMengenauftragVor(41,1); server.abbruchVorCommit=true;
        assertEquals(Status.AUSSTEHEND,sync()); assertNull(server.leseStand().menge);
        assertEquals(0,server.anzahl("quittung")); assertEquals(a,lokal.bereiteMengenauftragVor(41,1));
        assertEquals(Status.BESTAETIGT,sync()); assertEquals("2",server.leseStand().token);
    }
    @Test public void abbruchNachCommitWirdNachNeustartOhneNeuesSchreibenQuittiert() {
        aendern(lokal,5,""); server.abbruchNachCommit=true;
        assertEquals(Status.AUSSTEHEND,sync()); assertFalse(freigegeben());
        lokal.close(); server.close();
        lokal=new LokalerInventurSpeicher(context,lokalName); server=new IsolierterMengenserver(context,serverName);
        assertEquals(Status.BESTAETIGT,sync()); assertEquals(0,server.verarbeitungen);
        assertEquals("2",server.leseStand().token); assertTrue(freigegeben());
    }
    @Test public void nichtErreichbareAuftragspruefungStartetKeinenBlindenVersuch() {
        aendern(lokal,5,""); server.abfrageNichtErreichbar=true;
        assertEquals(Status.AUSSTEHEND,sync()); assertEquals(0,server.verarbeitungen); assertFalse(freigegeben());
    }
    @Test public void neueLokaleRevisionWaehrendDerUebertragungBleibtOffen() {
        aendern(lokal,5,""); server.nachCommit=()->aendern(lokal,9,"");
        assertEquals(Status.BESTAETIGT,sync()); assertEquals(Integer.valueOf(5),server.leseStand().menge);
        assertEquals(Integer.valueOf(9),lokal.ladeArtikel(41).get(0).getZaehlMenge());
        assertEquals("1",feld("menge_lokal_geaendert")); assertFalse(freigegeben());
        assertEquals(Status.BESTAETIGT,sync()); assertEquals(Integer.valueOf(9),server.leseStand().menge);
        assertTrue(freigegeben());
    }
    @Test public void fehlenderBestaetigterErgebnisstandWirdNichtErfunden() {
        lokal.getWritableDatabase().execSQL("UPDATE artikel SET bestaetigter_serverstand=NULL");
        aendern(lokal,5,""); assertEquals(Status.ZUORDNUNG_UNGEKLAERT,sync());
        assertEquals(0,server.verarbeitungen); assertFalse(freigegeben());
    }
    @Test public void geschuetzterAltbestandOhneSqlIdentitaetWirdAbgewiesen() {
        lokal.getWritableDatabase().execSQL("UPDATE artikel SET sql_positions_id=NULL,quell_blattnummer=NULL");
        aendern(lokal,5,""); assertEquals(Status.ZUORDNUNG_UNGEKLAERT,sync()); assertEquals(0,server.verarbeitungen);
    }
    @Test public void falscherDurchgangOderVerschobenePositionWirdNichtBeschrieben() {
        server.getWritableDatabase().execSQL("UPDATE ergebnis SET durchgang='TEST-RUNDE-B',pos=9");
        aendern(lokal,5,""); assertEquals(Status.KONFLIKT,sync()); assertNull(server.leseStand().menge);
        assertEquals(1,server.anzahl("ergebnis")); assertFalse(freigegeben());
    }
    @Test public void fehlendeErgebniszeileFuehrtZuKonfliktUndKeinemInsert() {
        server.getWritableDatabase().delete("ergebnis",null,null);
        aendern(lokal,5,""); assertEquals(Status.KONFLIKT,sync()); assertEquals(0,server.anzahl("ergebnis"));
    }
    @Test public void mengenquittungBestaetigtWederNotizNochAbschluss() {
        aendern(lokal,5,"Offene Testnotiz"); lokal.markiereZaehlerlisteAlsAbgeschlossen(41);
        assertEquals(Status.BESTAETIGT,sync()); assertEquals("0",feld("menge_lokal_geaendert"));
        assertEquals("1",feld("notiz_lokal_geaendert"));
        assertEquals("Offene Testnotiz",lokal.ladeArtikel(41).get(0).getAnmerkung());
        assertTrue(lokal.ladeZaehlerlisteFuerId(41).istAbgeschlossen()); assertFalse(freigegeben());
        assertTrue(lokal.hatOffeneSynchronisierungFuerZaehlerliste(41));
    }
    @Test public void sqliteFehlerRolltDieGesamteLokaleQuittierungZurueck() {
        aendern(lokal,5,""); String vorher=feld("bestaetigter_serverstand");
        lokal.getWritableDatabase().execSQL("CREATE TRIGGER test_quittung BEFORE UPDATE ON einzel_sync_auftrag "
                + "WHEN NEW.status='BESTAETIGT' BEGIN SELECT RAISE(ABORT,'Isolierter Quittierungsfehler'); END");
        assertEquals(Status.AUSSTEHEND,sync()); assertEquals(vorher,feld("bestaetigter_serverstand"));
        assertEquals("1",feld("menge_lokal_geaendert")); assertFalse(freigegeben());
        lokal.getWritableDatabase().execSQL("DROP TRIGGER test_quittung");
        assertEquals(Status.BESTAETIGT,sync()); assertEquals(1,server.verarbeitungen); assertTrue(freigegeben());
    }
    @Test public void schemaUpgradeBewahrtLokaleAenderungenUndWarteschlange() {
        aendern(lokal,0,"Testnotiz erhalten");
        lokal.getWritableDatabase().execSQL("DROP TABLE einzel_sync_auftrag");
        lokal.getWritableDatabase().setVersion(11); lokal.close();
        lokal=new LokalerInventurSpeicher(context,lokalName);
        assertEquals(13,lokal.getReadableDatabase().getVersion());
        assertEquals(Integer.valueOf(0),lokal.ladeArtikel(41).get(0).getZaehlMenge());
        assertEquals("Testnotiz erhalten",lokal.ladeArtikel(41).get(0).getAnmerkung());
        assertTrue(lokal.hatOffeneSynchronisierungFuerZaehlerliste(41));
        assertNotNull(lokal.bereiteMengenauftragVor(41,1));
    }
}
