package com.example.myapplication;

import android.content.Context;
import android.content.Intent;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class HauptmenueSperreTest {
    private Context context;
    private String name;
    private LokalerInventurSpeicher speicher;
    private ArtikelRepository repository;

    @Before public void vorbereiten() {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        name="hauptmenuesperre_"+UUID.randomUUID()+".db";
        speicher=new LokalerInventurSpeicher(context,name);
        speicher.initialisiereZaehlerlisten(Arrays.asList(
                new ZaehlerlisteDto(41,"Test A","41","Test","",true,false),
                new ZaehlerlisteDto(42,"Test B","42","Test","",true,false)));
        speicher.initialisiereArtikel(41,Arrays.asList(
                new ArtikelDto(1,"TEST-A","Test","L1",3,1,null),
                new ArtikelDto(2,"TEST-B","Test","L1",3,1,0)));
        repository=new ArtikelRepository(new NurLesen(),speicher) {
            @Override public List<Zaehlerliste> ladeZaehlerlisten() { return speicher.ladeZaehlerlisten(); }
            @Override public Listenuebersicht aktualisiereListenuebersicht() {
                return Listenuebersicht.lokal(speicher.ladeZaehlerlisten());
            }
        };
    }
    @After public void aufraeumen() { speicher.close(); context.deleteDatabase(name); }

    @Test public void ohneLokaleAenderungenIstRueckkehrErlaubt() {
        assertTrue(repository.istHauptmenueRueckkehrErlaubt());
    }
    @Test public void nurKopfaenderungBleibtLokalUndErlaubtNavigation() {
        repository.speichereZaehlmengenNurLokal(41,"Neuer Zaehler","Schreiber",speicher.ladeArtikel(41));
        assertTrue(repository.istHauptmenueRueckkehrErlaubt());
        assertTrue(repository.synchronisiereBeimVerlassen(41,false).istErfolgreich());
        assertEquals("Neuer Zaehler",speicher.ladeZaehlerlisteFuerId(41).getGespeicherterZaehlerName());
        assertTrue(speicher.hatUnbestaetigteLokaleAenderungen());
        assertTrue(speicher.hatOffeneSynchronisierungFuerZaehlerliste(41));
    }
    @Test public void nurNotizBleibtUnquittiertUndErlaubtNavigation() {
        List<Artikel> artikel=speicher.ladeArtikel(41);artikel.get(0).setAnmerkung("Nur lokal");
        repository.speichereZaehlmengenNurLokal(41,null,null,artikel);
        assertTrue(repository.synchronisiereBeimVerlassen(41,false).istErfolgreich());
        assertTrue(repository.istHauptmenueRueckkehrErlaubt());
        assertTrue(speicher.hatUnbestaetigteLokaleAenderungen());
        assertEquals("Nur lokal",speicher.ladeArtikel(41).get(0).getAnmerkung());
    }
    @Test public void mengenpruefungUnterscheidetAktuellesBlattUndGesamtbestand() {
        List<Artikel> artikel=speicher.ladeArtikel(41);artikel.get(0).setZaehlMenge(9);
        repository.speichereZaehlmengenNurLokal(41,null,null,artikel);
        assertTrue(repository.hatOffeneMengensynchronisierung(41));
        assertFalse(repository.hatOffeneMengensynchronisierung(42));
        assertFalse(repository.istHauptmenueRueckkehrErlaubt());
        assertTrue(repository.synchronisiereBeimVerlassen(42,false).istErfolgreich());
        assertFalse(repository.synchronisiereBeimVerlassen(41,false).istErfolgreich());
    }
    @Test public void alterListenauftragOhneMengenSperrtNicht() {
        speicher.merkeZaehlerlisteFuerSynchronisierung(41);
        assertTrue(repository.istSqlSchreibenGesperrt());
        assertTrue(repository.istHauptmenueRueckkehrErlaubt());
        assertTrue(speicher.hatOffeneSynchronisierungFuerZaehlerliste(41));
    }
    @Test public void geloeschterAuftragQuittiertKeineMengenrevision() {
        List<Artikel> artikel=speicher.ladeArtikel(41);
        artikel.get(0).setZaehlMenge(0); artikel.get(1).setZaehlMenge(null);
        repository.speichereZaehlmengenNurLokal(41,"","",artikel);
        speicher.entferneZaehlerlisteAusSynchronisierung(41);
        assertFalse(repository.istHauptmenueRueckkehrErlaubt());
        assertEquals(Integer.valueOf(0),speicher.ladeArtikel(41).get(0).getZaehlMenge());
        assertNull(speicher.ladeArtikel(41).get(1).getZaehlMenge());
    }
    @Test public void notizenUndAbschlussBleibenOhneVertragUnbestaetigt() {
        List<Artikel> artikel=speicher.ladeArtikel(41); artikel.get(0).setAnmerkung("Offene Notiz");
        repository.speichereZaehlmengenNurLokal(41,"","",artikel);
        speicher.markiereZaehlerlisteAlsAbgeschlossen(41);
        speicher.entferneZaehlerlisteAusSynchronisierung(41);
        assertTrue(repository.istHauptmenueRueckkehrErlaubt());
        assertTrue(speicher.hatUnbestaetigteLokaleAenderungen());
        assertEquals("Offene Notiz",speicher.ladeArtikel(41).get(0).getAnmerkung());
        assertTrue(speicher.ladeZaehlerlisteFuerId(41).istAbgeschlossen());
    }
    @Test public void alleinigeAbschlussaenderungSperrtNicht() {
        speicher.markiereZaehlerlisteAlsAbgeschlossen(42);
        speicher.entferneZaehlerlisteAusSynchronisierung(42);
        assertTrue(repository.istHauptmenueRueckkehrErlaubt());
        assertTrue(speicher.hatUnbestaetigteLokaleAenderungen());
    }
    @Test public void generischeBestaetigungLoeschtKeineOffenenKennzeichen() {
        List<Artikel> artikel=speicher.ladeArtikel(41); artikel.get(0).setZaehlMenge(7);
        repository.speichereZaehlmengenNurLokal(41,"","",artikel);
        speicher.entferneZaehlerlisteAusSynchronisierung(41);
        speicher.getWritableDatabase().execSQL("UPDATE artikel SET bestaetigter_serverstand='Testbestaetigung'");
        assertFalse(repository.istHauptmenueRueckkehrErlaubt());
    }

    @Test public void historischeRevisionenOhneOffeneAenderungenSperrenNicht() {
        speicher.getWritableDatabase().execSQL("UPDATE artikel SET lokale_revision=7");
        speicher.getWritableDatabase().execSQL("UPDATE zaehlerliste SET lokale_revision=9 WHERE id=41");
        assertTrue(repository.istHauptmenueRueckkehrErlaubt());
        Synchronisierungsdiagnose d=repository.holeSynchronisierungsdiagnose(41);
        assertEquals(2,d.getPositivePositionsrevisionen());
        assertEquals(1,d.getPositiveKopfrevisionen());
        assertEquals(3,d.getRevisionenOhneKennzeichen());
        assertFalse(d.hatOffeneAenderungen());
    }

    @Test public void quittierterTestzustandMitHistorischerRevisionIstFreigabefaehig() {
        // Isolierte Fixture eines bereits quittierten Zustands, keine Quittierung durch die App.
        speicher.getWritableDatabase().execSQL("UPDATE artikel SET lokale_revision=3, "
                + "sync_unklar=0, bestaetigter_serverstand='Quittierter Testzustand'");
        assertTrue(repository.istHauptmenueRueckkehrErlaubt());
        assertEquals(2,repository.holeSynchronisierungsdiagnose(41).getGespeicherteServerstaende());
    }

    @Test public void neueAenderungNachFrueheremServerstandSperrtWieder() {
        speicher.getWritableDatabase().execSQL("UPDATE artikel SET lokale_revision=3, "
                + "sync_unklar=0, bestaetigter_serverstand='Frueherer Teststand'");
        List<Artikel> artikel=speicher.ladeArtikel(41); artikel.get(0).setZaehlMenge(0);
        repository.speichereZaehlmengenNurLokal(41,"","",artikel);
        assertFalse(repository.istHauptmenueRueckkehrErlaubt());
        assertEquals(1,repository.holeSynchronisierungsdiagnose(41).getGeaendertePositionen());
    }

    @Test public void diagnoseTrenntPositionenKopfAltbestandUndWarteschlange() {
        List<Artikel> artikel=speicher.ladeArtikel(41);
        artikel.get(0).setZaehlMenge(0); artikel.get(0).setAnmerkung("Testnotiz");
        artikel.get(1).setZaehlMenge(null);
        repository.speichereZaehlmengenNurLokal(41,"Testzaehler","Testschreiber",artikel);
        speicher.markiereZaehlerlisteAlsAbgeschlossen(41);
        Synchronisierungsdiagnose d=repository.holeSynchronisierungsdiagnose(41);
        assertEquals(1,d.getWarteschlange());
        assertEquals(2,d.getGeaendertePositionen());
        assertEquals(1,d.getGeaenderteKoepfe());
        assertEquals(2,d.getUngeklaerteAltpositionen());
        assertEquals(0,d.getGespeicherteServerstaende());
        assertEquals(0,d.getRevisionenOhneKennzeichen());
        assertEquals(0,repository.holeSynchronisierungsdiagnose(42).getGeaendertePositionen());
        assertTrue(speicher.hatOffeneSynchronisierungFuerZaehlerliste(41));
        assertEquals("Testnotiz",speicher.ladeArtikel(41).get(0).getAnmerkung());
        assertEquals(Integer.valueOf(0),speicher.ladeArtikel(41).get(0).getZaehlMenge());
        assertNull(speicher.ladeArtikel(41).get(1).getZaehlMenge());
    }

    @Test public void gesamtdiagnoseZaehltAuchAuftraegeAusAnderemBlatt() {
        speicher.merkeZaehlerlisteFuerSynchronisierung(42);
        Synchronisierungsdiagnose gesamt=repository.holeSynchronisierungsdiagnose(null);
        assertTrue(gesamt.istVerfuegbar());
        assertEquals(1,gesamt.getWarteschlange());
        assertEquals(0,repository.holeSynchronisierungsdiagnose(41).getWarteschlange());
        assertEquals(1,repository.holeSynchronisierungsdiagnose(42).getWarteschlange());
        assertTrue(speicher.hatOffeneSynchronisierungFuerZaehlerliste(42));
    }
    @Test public void sqliteFehlerFuehrtNichtZurFreigabe() {
        try(LokalerInventurSpeicher unlesbar=new LokalerInventurSpeicher(context,name) {
            @Override public synchronized boolean hatOffeneMengensynchronisierung(Integer blattId) {
                throw new android.database.sqlite.SQLiteException("Isolierter Lesefehler");
            }
        }) {
            assertFalse(new ArtikelRepository(new NurLesen(),unlesbar).istHauptmenueRueckkehrErlaubt());
        }
    }
    @Test public void rueckkehrBleibtGesperrtUndSqlModusBietetKeinenRetry() throws Exception {
        List<Artikel> artikel=speicher.ladeArtikel(41); artikel.get(0).setZaehlMenge(5);
        repository.speichereZaehlmengenNurLokal(41,null,null,artikel);
        Field container=AppContainer.class.getDeclaredField("apiArtikelRepository"); container.setAccessible(true);
        Object vorher=container.get(null);
        try {
            container.set(null,repository);
            try(ActivityScenario<ZaehlerlistenActivity> scenario=ActivityScenario.launch(
                    new Intent(context,ZaehlerlistenActivity.class)
                            .putExtra(ZaehlerlistenActivity.EXTRA_AUSGEWAEHLTE_ZAEHLERLISTEN_ID,41))) {
                scenario.onActivity(a->a.findViewById(R.id.zaehlerlistenZurueckButton).performClick());
                onView(withText(R.string.sync_hauptmenue_blockiert_titel)).inRoot(isDialog()).check(matches(isDisplayed()));
                onView(withText(R.string.sync_hauptmenue_dialog_retry)).inRoot(isDialog()).check(doesNotExist());
                onView(withSubstring("SQL-Übertragung deaktiviert")).inRoot(isDialog())
                        .check(matches(withSubstring("Warteschlangeneinträge: 1")));
                assertEquals(Lifecycle.State.RESUMED,scenario.getState());
                assertTrue(speicher.hatOffeneMengensynchronisierung(41));
            }
        } finally { container.set(null,vorher); }
    }

    private static class NurLesen implements InventurRemoteDataSource {
        @Override public boolean istSchreibgeschuetzt() { return true; }
        @Override public void pruefeVerbindung() { }
        @Override public List<ZaehlerlisteDto> ladeZaehlerlisten() { throw new AssertionError("Keine Serverdaten im Test"); }
        @Override public List<ArtikelDto> ladeArtikel(int id) { throw new AssertionError("Keine Serverdaten im Test"); }
        @Override public ZaehlmengenSpeicherAntwort speichereZaehlmengen(ZaehlmengenSpeicherAnfrage a) { throw new AssertionError("Keine SQL-Schreibfreigabe"); }
        @Override public void markiereZaehlerlisteAlsAbgeschlossen(int id) { throw new AssertionError("Keine SQL-Schreibfreigabe"); }
        @Override public String holeLetzteDiagnose() { return ""; }
    }
}
