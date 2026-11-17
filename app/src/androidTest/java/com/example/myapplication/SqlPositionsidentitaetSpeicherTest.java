package com.example.myapplication;

import android.content.Context;
import android.database.Cursor;
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
public class SqlPositionsidentitaetSpeicherTest {
    private Context context;
    private String name;
    private LokalerInventurSpeicher speicher;

    @Before public void vorbereiten() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        name = "positionsidentitaet_" + UUID.randomUUID() + ".db";
        speicher = new LokalerInventurSpeicher(context,name);
        speicher.initialisiereZaehlerlisten(Arrays.asList(
                new ZaehlerlisteDto(41,"Test","41","Test","",true,false),
                new ZaehlerlisteDto(42,"Test","42","Test","",true,false)));
    }

    @After public void aufraeumen() { speicher.close(); context.deleteDatabase(name); }

    private ArtikelDto dto(long id, int blatt, int pos, Integer menge) {
        return new ArtikelDto(pos,"TEST-A","Testartikel","L1",3,null,1,menge,"",id,blatt);
    }

    private String text(String sql) {
        try (Cursor c = speicher.getReadableDatabase().rawQuery(sql,null)) {
            assertTrue(c.moveToFirst()); return c.isNull(0) ? null : c.getString(0);
        }
    }

    @Test public void identitaetBleibtNachNeustartMitNullUndNullmengeErhalten() {
        speicher.initialisiereArtikel(41,Arrays.asList(dto(701,41,1,null),dto(702,41,2,0)));
        speicher.close(); speicher = new LokalerInventurSpeicher(context,name);
        List<Artikel> artikel = speicher.ladeArtikel(41);
        assertEquals(13,speicher.getReadableDatabase().getVersion());
        assertEquals(Long.valueOf(701),artikel.get(0).getSqlPositionsId());
        assertEquals(Long.valueOf(702),artikel.get(1).getSqlPositionsId());
        assertEquals(Integer.valueOf(41),artikel.get(0).getQuellBlattnummer());
        assertNull(artikel.get(0).getZaehlMenge());
        assertEquals(Integer.valueOf(0),artikel.get(1).getZaehlMenge());
        assertEquals("1",text("SELECT sync_unklar FROM artikel WHERE pos_nr=1"));
        assertEquals("0",text("SELECT lokale_revision FROM artikel WHERE pos_nr=1"));
        assertNull(text("SELECT quell_stichtag FROM artikel WHERE pos_nr=1"));
        assertNull(text("SELECT bestaetigter_serverstand FROM artikel WHERE pos_nr=1"));
        assertFalse(speicher.hatOffeneSynchronisierung());
    }

    @Test public void gleichePositionsnummerInVerschiedenenBlaetternUndDoppelteArtikelnummerSindErlaubt() {
        speicher.initialisiereArtikel(41,Arrays.asList(dto(701,41,1,null),dto(702,41,2,null)));
        speicher.initialisiereArtikel(42,Collections.singletonList(dto(703,42,1,null)));
        assertEquals(2,speicher.ladeArtikel(41).size());
        assertEquals(Integer.valueOf(42),speicher.ladeArtikel(42).get(0).getQuellBlattnummer());
        assertEquals(Long.valueOf(703),speicher.ladeArtikel(42).get(0).getSqlPositionsId());
    }

    @Test public void altbestandWirdNichtNachtraeglichZugeordnetOderUeberschrieben() {
        speicher.initialisiereArtikel(41,Collections.singletonList(
                new ArtikelDto(1,"TEST-A","Alt","L1",3,null,1,null)));
        List<Artikel> artikel = speicher.ladeArtikel(41);
        artikel.get(0).setZaehlMenge(0); artikel.get(0).setAnmerkung("Offene Notiz");
        speicher.speichereZaehlmengen(41,"Zaehler","Schreiber",artikel);
        String vorher = text("SELECT geaendert_am FROM sync_warteschlange WHERE zaehlerlisten_id=41");
        speicher.initialisiereArtikel(41,Collections.singletonList(dto(701,41,1,99)));
        Artikel erhalten = speicher.ladeArtikel(41).get(0);
        assertNull(erhalten.getSqlPositionsId());
        assertNull(erhalten.getQuellBlattnummer());
        assertEquals(Integer.valueOf(0),erhalten.getZaehlMenge());
        assertEquals("Offene Notiz",erhalten.getAnmerkung());
        assertEquals("1",text("SELECT lokale_revision FROM artikel"));
        assertEquals("1",text("SELECT sync_unklar FROM artikel"));
        assertEquals(vorher,text("SELECT geaendert_am FROM sync_warteschlange WHERE zaehlerlisten_id=41"));
    }

    @Test public void verschobeneIdentitaetWirdWederUmgezogenNochDupliziert() {
        speicher.initialisiereArtikel(41,Collections.singletonList(dto(701,41,1,null)));
        speicher.initialisiereArtikel(41,Collections.singletonList(dto(701,41,9,8)));
        speicher.initialisiereArtikel(42,Collections.singletonList(dto(701,42,1,8)));
        assertEquals(1,speicher.ladeArtikel(41).size());
        assertEquals(1,speicher.ladeArtikel(41).get(0).getPositionsNummer());
        assertNull(speicher.ladeArtikel(41).get(0).getZaehlMenge());
        assertTrue(speicher.ladeArtikel(42).isEmpty());
        assertEquals("1",text("SELECT sync_unklar FROM artikel"));
    }

    @Test public void falschesBlattOderDoppelteIdentitaetHinterlaesstKeinenTeilimport() {
        for (List<ArtikelDto> antwort : Arrays.asList(
                Arrays.asList(dto(701,41,1,null),dto(701,41,2,null)),
                Arrays.asList(dto(701,41,1,null),dto(702,42,2,null)))) {
            try { speicher.initialisiereArtikel(41,antwort); fail("Mehrdeutige Zuordnung muss scheitern"); }
            catch (IllegalArgumentException erwartet) { }
            assertTrue(speicher.ladeArtikel(41).isEmpty());
            assertFalse(speicher.hatOffeneSynchronisierung());
        }
    }

    @Test public void fehlendeQuellidentitaetErzeugtKeineErfundeneSqlId() {
        speicher.initialisiereArtikel(41,Collections.singletonList(dto(0,41,1,null)));
        assertNull(speicher.ladeArtikel(41).get(0).getSqlPositionsId());
        assertEquals("1",text("SELECT sync_unklar FROM artikel"));
        assertNull(text("SELECT sql_positions_id FROM artikel"));
    }

    @Test public void abgeschlossenesBlattErhaeltKeineNeuenPositionen() {
        speicher.markiereZaehlerlisteAlsAbgeschlossen(41);
        String vorher = text("SELECT geaendert_am FROM sync_warteschlange WHERE zaehlerlisten_id=41");
        speicher.initialisiereArtikel(41,Collections.singletonList(dto(701,41,1,null)));
        assertTrue(speicher.ladeArtikel(41).isEmpty());
        assertTrue(speicher.ladeZaehlerlisteFuerId(41).istAbgeschlossen());
        assertEquals(vorher,text("SELECT geaendert_am FROM sync_warteschlange WHERE zaehlerlisten_id=41"));
    }

    @Test public void falscheIdentitaetBeimSpeichernRolltDieTransaktionZurueck() {
        speicher.initialisiereArtikel(41,Collections.singletonList(dto(701,41,1,null)));
        Artikel falsch = ArtikelMapper.mappe(dto(702,41,1,8));
        try { speicher.speichereZaehlmengen(41,"Neu","Neu",Collections.singletonList(falsch)); fail("Identitaet stimmt nicht"); }
        catch (IllegalArgumentException erwartet) { }
        assertNull(speicher.ladeArtikel(41).get(0).getZaehlMenge());
        assertEquals("0",text("SELECT lokale_revision FROM artikel"));
        assertEquals("",text("SELECT zaehler_name FROM zaehlerliste WHERE id=41"));
        assertFalse(speicher.hatOffeneSynchronisierung());
    }
}
