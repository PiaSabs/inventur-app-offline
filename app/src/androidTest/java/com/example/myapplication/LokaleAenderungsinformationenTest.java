package com.example.myapplication;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LokaleAenderungsinformationenTest {
    private Context context;
    private String name;
    private LokalerInventurSpeicher speicher;

    @Before public void vorbereiten() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        name = "lokale_aenderungen_" + UUID.randomUUID() + ".db";
    }

    @After public void aufraeumen() {
        if (speicher != null) speicher.close();
        context.deleteDatabase(name);
    }

    private void neuAnlegen() {
        speicher = new LokalerInventurSpeicher(context, name);
        speicher.initialisiereZaehlerlisten(Collections.singletonList(
                new ZaehlerlisteDto(41, "Testliste", "41", "Test", "", true, false)));
        speicher.initialisiereArtikel(41, Arrays.asList(
                new ArtikelDto(1, "TEST-A", "Artikel A", "L1", 7, null, 1, null),
                new ArtikelDto(2, "TEST-B", "Artikel B", "L2", 8, 0, 1, 0)));
    }

    private long zahl(String sql) {
        try (Cursor c = speicher.getReadableDatabase().rawQuery(sql, null)) {
            assertTrue(c.moveToFirst());
            return c.getLong(0);
        }
    }

    private String text(String sql) {
        try (Cursor c = speicher.getReadableDatabase().rawQuery(sql, null)) {
            assertTrue(c.moveToFirst());
            return c.isNull(0) ? null : c.getString(0);
        }
    }

    private void speichern(List<Artikel> artikel) {
        speicher.speichereZaehlmengen(41, "", "", artikel);
    }

    private void warteschlangeBlockieren() {
        speicher.getWritableDatabase().execSQL("CREATE TRIGGER test_warteschlange BEFORE INSERT ON sync_warteschlange "
                + "BEGIN SELECT RAISE(ABORT, 'Testfehler'); END");
    }

    @Test public void migrationErhaeltAltbestandUndSchuetztOhneServerbestaetigung() {
        try (SQLiteDatabase db = context.openOrCreateDatabase(name, 0, null)) {
            db.execSQL("CREATE TABLE zaehlerliste (id INTEGER PRIMARY KEY, bezeichnung TEXT NOT NULL, "
                    + "blattnummer TEXT NOT NULL, bereich TEXT NOT NULL, stichtag TEXT NOT NULL DEFAULT '', "
                    + "letztes_zaehldatum TEXT NOT NULL, faellig INTEGER NOT NULL, abgeschlossen INTEGER NOT NULL DEFAULT 0, "
                    + "zaehler_name TEXT NOT NULL DEFAULT '', schreiber_name TEXT NOT NULL DEFAULT '', zaehl_typ TEXT NOT NULL DEFAULT '')");
            db.execSQL("CREATE TABLE artikel (zaehlerlisten_id INTEGER NOT NULL, pos_nr INTEGER NOT NULL, "
                    + "artikelnummer TEXT NOT NULL, bezeichnung TEXT NOT NULL, lagerort TEXT NOT NULL DEFAULT '', "
                    + "ist_menge INTEGER NOT NULL, mindestbestand INTEGER NOT NULL, zaehl_menge INTEGER, "
                    + "anmerkung TEXT NOT NULL DEFAULT '', letzte_zaehlmenge INTEGER, PRIMARY KEY(zaehlerlisten_id,pos_nr))");
            db.execSQL("CREATE TABLE zaehlerlisten_status (zaehlerlisten_id INTEGER PRIMARY KEY, "
                    + "zaehler_name TEXT NOT NULL DEFAULT '', schreiber_name TEXT NOT NULL DEFAULT '', abgeschlossen INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE TABLE sync_warteschlange (zaehlerlisten_id INTEGER PRIMARY KEY, geaendert_am TEXT NOT NULL)");
            db.execSQL("INSERT INTO zaehlerliste VALUES (41,'Altbestand','41','Test','2026-01-02','',1,1,'Zaehler','Schreiber','WO')");
            db.execSQL("INSERT INTO zaehlerlisten_status VALUES (41,'Zaehler','Schreiber',1)");
            db.execSQL("INSERT INTO sync_warteschlange VALUES (41,'unveraendert')");
            db.execSQL("INSERT INTO artikel VALUES (41,3,'A','A','L1',17,2,NULL,'Notiz NULL',0)");
            db.execSQL("INSERT INTO artikel VALUES (41,8,'B','B','L2',19,4,0,'Notiz Nullmenge',NULL)");
            db.execSQL("INSERT INTO artikel VALUES (41,12,'C','C','L3',21,6,23,'Notiz Menge',9)");
            db.execSQL("INSERT INTO zaehlerliste VALUES (42,'Ungeklaert','42','Test','','',1,0,'','','WO')");
            db.execSQL("INSERT INTO artikel VALUES (42,5,'D','D','L4',3,1,11,'Ohne Auftrag',NULL)");
            db.setVersion(10);
        }
        speicher = new LokalerInventurSpeicher(context, name);
        List<Artikel> artikel = speicher.ladeArtikel(41);
        assertEquals(11, speicher.getReadableDatabase().getVersion());
        assertEquals(3, artikel.size());
        assertEquals(3, artikel.get(0).getPositionsNummer());
        assertEquals(8, artikel.get(1).getPositionsNummer());
        assertEquals(12, artikel.get(2).getPositionsNummer());
        assertNull(artikel.get(0).getZaehlMenge());
        assertEquals(Integer.valueOf(0), artikel.get(1).getZaehlMenge());
        assertEquals(Integer.valueOf(23), artikel.get(2).getZaehlMenge());
        assertEquals(Integer.valueOf(0), artikel.get(0).getLetzteZaehlmenge());
        assertNull(artikel.get(1).getLetzteZaehlmenge());
        assertEquals("Notiz NULL", artikel.get(0).getAnmerkung());
        assertEquals("Notiz Nullmenge", artikel.get(1).getAnmerkung());
        assertEquals("Notiz Menge", artikel.get(2).getAnmerkung());
        assertEquals(17, artikel.get(0).getIstMenge());
        assertEquals(19, artikel.get(1).getIstMenge());
        assertEquals(21, artikel.get(2).getIstMenge());
        assertTrue(speicher.ladeZaehlerlisteFuerId(41).istAbgeschlossen());
        assertEquals("2026-01-02", text("SELECT stichtag FROM zaehlerliste"));
        assertEquals("Zaehler", text("SELECT zaehler_name FROM zaehlerlisten_status"));
        assertEquals("unveraendert", text("SELECT geaendert_am FROM sync_warteschlange"));
        assertEquals(3, zahl("SELECT COUNT(*) FROM artikel WHERE zaehlerlisten_id=41 AND sync_unklar=1 AND lokale_revision=0 "
                + "AND menge_lokal_geaendert=0 AND notiz_lokal_geaendert=0 AND bestaetigter_serverstand IS NULL "
                + "AND sql_positions_id IS NULL AND quell_blattnummer IS NULL AND quell_stichtag IS NULL"));
        assertEquals(1, zahl("SELECT sync_unklar FROM zaehlerliste"));
        assertNull(text("SELECT bestaetigter_serverstand FROM zaehlerliste"));
        assertFalse(speicher.hatOffeneSynchronisierungFuerZaehlerliste(42));
        assertEquals(1, zahl("SELECT sync_unklar FROM artikel WHERE zaehlerlisten_id=42"));
        assertEquals(Integer.valueOf(11), speicher.ladeArtikel(42).get(0).getZaehlMenge());
        speicher.initialisiereArtikel(42, Collections.singletonList(
                new ArtikelDto(5, "D", "D", "L4", 3, null, 1, 99)));
        assertEquals(Integer.valueOf(11), speicher.ladeArtikel(42).get(0).getZaehlMenge());
        assertEquals("Ohne Auftrag", speicher.ladeArtikel(42).get(0).getAnmerkung());
        speicher.close();
        speicher = new LokalerInventurSpeicher(context, name);
        assertEquals(3, speicher.ladeArtikel(41).size());
        assertEquals(4, zahl("SELECT SUM(sync_unklar) FROM artikel"));
    }

    @Test public void neuePositionenSindOhneNachweisEbenfallsGeschuetzt() {
        neuAnlegen();
        assertEquals(2, zahl("SELECT COUNT(*) FROM artikel WHERE sync_unklar=1 AND bestaetigter_serverstand IS NULL"));
        assertEquals(0, zahl("SELECT SUM(lokale_revision) FROM artikel"));
        assertFalse(speicher.hatOffeneSynchronisierung());
    }

    @Test public void nullZuNullmengeErhoehtRevisionUndMarkiertNurMenge() {
        neuAnlegen();
        List<Artikel> artikel = speicher.ladeArtikel(41);
        artikel.get(0).setZaehlMenge(0);
        speichern(artikel);
        assertEquals(Integer.valueOf(0), speicher.ladeArtikel(41).get(0).getZaehlMenge());
        assertEquals(1, zahl("SELECT menge_lokal_geaendert FROM artikel WHERE pos_nr=1"));
        assertEquals(0, zahl("SELECT notiz_lokal_geaendert FROM artikel WHERE pos_nr=1"));
        assertEquals(1, zahl("SELECT lokale_revision FROM artikel WHERE pos_nr=1"));
        assertEquals(0, zahl("SELECT lokale_revision FROM artikel WHERE pos_nr=2"));
        assertTrue(speicher.hatOffeneSynchronisierungFuerZaehlerliste(41));
    }

    @Test public void reineNotizaenderungWirdGetrenntErfasst() {
        neuAnlegen();
        List<Artikel> artikel = speicher.ladeArtikel(41);
        artikel.get(0).setAnmerkung("Lokale Notiz");
        speichern(artikel);
        assertEquals("Lokale Notiz", speicher.ladeArtikel(41).get(0).getAnmerkung());
        assertNull(speicher.ladeArtikel(41).get(0).getZaehlMenge());
        assertEquals(1, zahl("SELECT notiz_lokal_geaendert FROM artikel WHERE pos_nr=1"));
        assertEquals(0, zahl("SELECT menge_lokal_geaendert FROM artikel WHERE pos_nr=1"));
        assertEquals(1, zahl("SELECT lokale_revision FROM artikel WHERE pos_nr=1"));
        assertTrue(speicher.hatOffeneSynchronisierungFuerZaehlerliste(41));
    }

    @Test public void wiederholteBearbeitungErhoehtRevisionOhneSchutzFreizugeben() {
        neuAnlegen();
        List<Artikel> artikel = speicher.ladeArtikel(41);
        artikel.get(0).setZaehlMenge(0);
        speichern(artikel);
        speichern(artikel);
        assertEquals(1, zahl("SELECT lokale_revision FROM artikel WHERE pos_nr=1"));
        artikel.get(0).setZaehlMenge(null);
        speichern(artikel);
        assertEquals(2, zahl("SELECT lokale_revision FROM artikel WHERE pos_nr=1"));
        assertEquals(1, zahl("SELECT menge_lokal_geaendert FROM artikel WHERE pos_nr=1"));
        assertEquals(1, zahl("SELECT sync_unklar FROM artikel WHERE pos_nr=1"));
        assertNull(text("SELECT bestaetigter_serverstand FROM artikel WHERE pos_nr=1"));
    }

    @Test public void warteschlangenfehlerRolltMengeNotizKopfUndRevisionZurueck() {
        neuAnlegen();
        warteschlangeBlockieren();
        List<Artikel> artikel = speicher.ladeArtikel(41);
        artikel.get(0).setZaehlMenge(5);
        artikel.get(0).setAnmerkung("Nicht gespeichert");
        try {
            speicher.speichereZaehlmengen(41, "Geaendert", "Geaendert", artikel);
            fail("Speicherung muss vollstaendig scheitern");
        } catch (SQLiteException erwartet) { }
        assertNull(speicher.ladeArtikel(41).get(0).getZaehlMenge());
        assertEquals("", speicher.ladeArtikel(41).get(0).getAnmerkung());
        assertEquals("", text("SELECT zaehler_name FROM zaehlerliste"));
        assertEquals(0, zahl("SELECT COUNT(*) FROM zaehlerlisten_status WHERE zaehlerlisten_id=41"));
        assertEquals(0, zahl("SELECT SUM(lokale_revision+menge_lokal_geaendert+notiz_lokal_geaendert) FROM artikel"));
        assertEquals(0, zahl("SELECT lokale_revision+kopf_lokal_geaendert FROM zaehlerliste"));
        assertFalse(speicher.hatOffeneSynchronisierung());
    }

    @Test public void fehlerErhaeltBereitsOffenenAuftragUndVorherigenStand() {
        neuAnlegen();
        List<Artikel> artikel = speicher.ladeArtikel(41);
        artikel.get(0).setZaehlMenge(4);
        speichern(artikel);
        String zeitpunkt = text("SELECT geaendert_am FROM sync_warteschlange");
        warteschlangeBlockieren();
        artikel.get(0).setZaehlMenge(9);
        try { speichern(artikel); fail("Fehler erwartet"); } catch (SQLiteException erwartet) { }
        assertEquals(Integer.valueOf(4), speicher.ladeArtikel(41).get(0).getZaehlMenge());
        assertEquals(1, zahl("SELECT lokale_revision FROM artikel WHERE pos_nr=1"));
        assertEquals(zeitpunkt, text("SELECT geaendert_am FROM sync_warteschlange"));
    }

    @Test public void abschlussUndWiederoeffnenSindAtomarVorgemerkt() {
        neuAnlegen();
        assertTrue(speicher.markiereZaehlerlisteAlsAbgeschlossen(41));
        assertTrue(speicher.ladeZaehlerlisteFuerId(41).istAbgeschlossen());
        assertEquals(1, zahl("SELECT abgeschlossen FROM zaehlerlisten_status"));
        assertTrue(speicher.hatOffeneSynchronisierungFuerZaehlerliste(41));
        assertEquals(1, zahl("SELECT lokale_revision FROM zaehlerliste"));
        assertEquals(1, zahl("SELECT abschluss_lokal_geaendert FROM zaehlerliste"));
        assertTrue(speicher.markiereZaehlerlisteAlsAbgeschlossen(41));
        assertEquals(1, zahl("SELECT lokale_revision FROM zaehlerliste"));
        assertTrue(speicher.markiereZaehlerlisteAlsOffen(41));
        assertFalse(speicher.ladeZaehlerlisteFuerId(41).istAbgeschlossen());
        assertEquals(0, zahl("SELECT abgeschlossen FROM zaehlerlisten_status"));
        assertEquals(2, zahl("SELECT lokale_revision FROM zaehlerliste"));
        assertTrue(speicher.hatOffeneSynchronisierungFuerZaehlerliste(41));
        assertFalse(speicher.markiereZaehlerlisteAlsAbgeschlossen(999));
        assertFalse(speicher.hatOffeneSynchronisierungFuerZaehlerliste(999));
    }

    @Test public void abschlussfehlerRolltBeideStatusspeicherZurueck() {
        neuAnlegen();
        warteschlangeBlockieren();
        try { speicher.markiereZaehlerlisteAlsAbgeschlossen(41); fail("Fehler erwartet"); }
        catch (SQLiteException erwartet) { }
        assertFalse(speicher.ladeZaehlerlisteFuerId(41).istAbgeschlossen());
        assertEquals(0, zahl("SELECT COUNT(*) FROM zaehlerlisten_status WHERE zaehlerlisten_id=41"));
        assertEquals(0, zahl("SELECT lokale_revision+abschluss_lokal_geaendert FROM zaehlerliste"));
        assertFalse(speicher.hatOffeneSynchronisierung());
    }

    @Test public void mehrdeutigeArtikelnummerRolltGesamteSpeicherungZurueck() {
        neuAnlegen();
        speicher.initialisiereArtikel(41, Collections.singletonList(
                new ArtikelDto(3, "TEST-A", "Zweite Position", "L3", 7, null, 1, null)));
        Artikel ohnePosition = new Artikel("TEST-A", "Artikel A", 7, 1, 8);
        try { speichern(Collections.singletonList(ohnePosition)); fail("Zuordnung ist nicht eindeutig"); }
        catch (IllegalArgumentException erwartet) { }
        assertEquals(0, zahl("SELECT SUM(lokale_revision) FROM artikel"));
        assertFalse(speicher.hatOffeneSynchronisierung());
    }

    @Test public void fremderArtikelAnAlterPositionWirdNichtBeschrieben() {
        neuAnlegen();
        List<Artikel> artikel = speicher.ladeArtikel(41);
        artikel.get(0).setZaehlMenge(5);
        Artikel fremd = new Artikel(2, "ANDERER-ARTIKEL", "Fremd", "L2", 8, 1, 9);
        try { speichern(Arrays.asList(artikel.get(0), fremd)); fail("Identitaet stimmt nicht ueberein"); }
        catch (IllegalArgumentException erwartet) { }
        assertNull(speicher.ladeArtikel(41).get(0).getZaehlMenge());
        assertEquals(Integer.valueOf(0), speicher.ladeArtikel(41).get(1).getZaehlMenge());
        assertFalse(speicher.hatOffeneSynchronisierung());
    }

    @Test public void erneuteInitialisierungErsetztKeineGeschuetztenDaten() {
        neuAnlegen();
        List<Artikel> artikel = speicher.ladeArtikel(41);
        artikel.get(0).setZaehlMenge(6);
        artikel.get(0).setAnmerkung("Behalten");
        speichern(artikel);
        speicher.markiereZaehlerlisteAlsAbgeschlossen(41);
        speicher.initialisiereZaehlerlisten(Collections.singletonList(
                new ZaehlerlisteDto(41, "Anderer Kopf", "41", "Test", "", true, false)));
        speicher.initialisiereArtikel(41, Collections.singletonList(
                new ArtikelDto(1, "VERSCHOBEN", "Anderer Artikel", "L1", 7, null, 1, 99)));
        assertEquals("TEST-A", speicher.ladeArtikel(41).get(0).getArtikelnummer());
        assertEquals(Integer.valueOf(6), speicher.ladeArtikel(41).get(0).getZaehlMenge());
        assertEquals("Behalten", speicher.ladeArtikel(41).get(0).getAnmerkung());
        assertEquals(1, zahl("SELECT lokale_revision FROM artikel WHERE pos_nr=1"));
        assertEquals("Testliste", text("SELECT bezeichnung FROM zaehlerliste"));
        assertTrue(speicher.ladeZaehlerlisteFuerId(41).istAbgeschlossen());
        assertTrue(speicher.hatOffeneSynchronisierungFuerZaehlerliste(41));
    }

    @Test public void kopfaenderungHatEigeneRevisionUndBleibtNachNeustartErhalten() {
        neuAnlegen();
        speicher.speichereZaehlmengen(41, "Zaehler", "Schreiber", speicher.ladeArtikel(41));
        speicher.close();
        speicher = new LokalerInventurSpeicher(context, name);
        assertEquals(1, zahl("SELECT lokale_revision FROM zaehlerliste"));
        assertEquals(1, zahl("SELECT kopf_lokal_geaendert FROM zaehlerliste"));
        assertEquals(0, zahl("SELECT SUM(lokale_revision) FROM artikel"));
        assertEquals("Zaehler", text("SELECT zaehler_name FROM zaehlerlisten_status"));
        assertTrue(speicher.hatOffeneSynchronisierungFuerZaehlerliste(41));
    }

    @Test public void speichernErhaeltAbschlussAuchOhneSeparateStatuszeile() {
        neuAnlegen();
        speicher.getWritableDatabase().execSQL("UPDATE zaehlerliste SET abgeschlossen=1 WHERE id=41");
        speichern(speicher.ladeArtikel(41));
        assertTrue(speicher.ladeZaehlerlisteFuerId(41).istAbgeschlossen());
        assertEquals(1, zahl("SELECT abgeschlossen FROM zaehlerlisten_status WHERE zaehlerlisten_id=41"));
        assertEquals(0, zahl("SELECT lokale_revision FROM zaehlerliste"));
        assertNull(text("SELECT bestaetigter_serverstand FROM zaehlerliste"));
    }
}
