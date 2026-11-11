package com.example.myapplication;

import android.app.Activity;
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
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ArtikelVerlassenTest {
    private Context context;
    private LokalerInventurSpeicher speicher;
    private String name;
    private Field container;
    private Object vorherigesRepository;
    private Benutzersitzung sitzung;
    private String vorherigerName;
    private volatile java.util.concurrent.CountDownLatch speichernBegonnen;
    private volatile java.util.concurrent.CountDownLatch speichernFreigeben;

    @Before public void vorbereiten() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        name = "artikel_verlassen_" + UUID.randomUUID() + ".db";
        speicher = new LokalerInventurSpeicher(context, name);
        speicher.initialisiereZaehlerlisten(Arrays.asList(
                new ZaehlerlisteDto(41, "Testgruppe", "41", "Test", "", "", true, false, "Zaehler", "Schreiber", "WO"),
                new ZaehlerlisteDto(42, "Testgruppe", "42", "Test", "", "", true, false, "Zaehler", "Schreiber", "WO")));
        for (int id : new int[]{41,42}) speicher.initialisiereArtikel(id, Collections.singletonList(
                new ArtikelDto(1, "TEST-A", "Testartikel", "L1", 3, null, 1, null)));
        container = AppContainer.class.getDeclaredField("apiArtikelRepository");
        container.setAccessible(true);
        vorherigesRepository = container.get(null);
        container.set(null, new ArtikelRepository(new KeinSql(), speicher) {
            @Override public List<Zaehlerliste> ladeZaehlerlisten() { return speicher.ladeZaehlerlisten(); }
            @Override public Zaehlerliste ladeZaehlerlisteFuerId(int id) { return speicher.ladeZaehlerlisteFuerId(id); }
            @Override public List<Artikel> ladeArtikel(int id) { return speicher.ladeArtikel(id); }
            @Override public Listenuebersicht aktualisiereListenuebersicht() { return Listenuebersicht.lokal(speicher.ladeZaehlerlisten()); }
            @Override public ZaehlmengenSpeicherAntwort speichereZaehlmengenNurLokal(int id, String zaehler,
                    String schreiber, List<Artikel> artikel) {
                if (speichernFreigeben != null) {
                    speichernBegonnen.countDown();
                    try {
                        if (!speichernFreigeben.await(5,java.util.concurrent.TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Testfreigabe fehlt");
                        }
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                }
                return super.speichereZaehlmengenNurLokal(id,zaehler,schreiber,artikel);
            }
        });
        sitzung = new Benutzersitzung(context);
        vorherigerName = sitzung.holeAngemeldetenZaehler();
        sitzung.setzeAngemeldetenZaehler("Testzaehler");
    }

    @After public void aufraeumen() throws Exception {
        container.set(null, vorherigesRepository);
        if (vorherigerName.isEmpty()) sitzung.abmelden(); else sitzung.setzeAngemeldetenZaehler(vorherigerName);
        speicher.close();
        context.deleteDatabase(name);
    }

    @Test public void version1SpeichertVorDemHintergrundwechsel() throws Exception { hintergrund(ArtikeluebersichtActivity.class); }
    @Test public void version2SpeichertVorDemHintergrundwechsel() throws Exception { hintergrund(ArtikeluebersichtV2Activity.class); }
    @Test public void version1SpeichertBeimSofortigenZurueck() throws Exception { zurueck(ArtikeluebersichtActivity.class); }
    @Test public void version2SpeichertBeimSofortigenZurueck() throws Exception { zurueck(ArtikeluebersichtV2Activity.class); }
    @Test public void version1BlattwechselWartetAufDieLetzteAenderung() throws Exception { schnellerWechsel(ArtikeluebersichtActivity.class); }
    @Test public void version2BlattwechselWartetAufDieLetzteAenderung() throws Exception { schnellerWechsel(ArtikeluebersichtV2Activity.class); }
    @Test public void version1BleibtBeiSqliteFehlerOffen() throws Exception { sqliteFehler(ArtikeluebersichtActivity.class); }
    @Test public void version2BleibtBeiSqliteFehlerOffen() throws Exception { sqliteFehler(ArtikeluebersichtV2Activity.class); }
    @Test public void version1SichertAuchOhneSchreibernamenLokal() throws Exception { ohneSchreiber(ArtikeluebersichtActivity.class); }
    @Test public void version2SichertAuchOhneSchreibernamenLokal() throws Exception { ohneSchreiber(ArtikeluebersichtV2Activity.class); }

    @Test public void blattlisteKannMitGesperrterUebertragungVerlassenWerden() throws Exception {
        speicher.merkeZaehlerlisteFuerSynchronisierung(41);
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(new Intent(context,ZaehlerlistenActivity.class)
                .putExtra(ZaehlerlistenActivity.EXTRA_AUSGEWAEHLTE_ZAEHLERLISTEN_ID,41))) {
            scenario.onActivity(a -> a.findViewById(R.id.zaehlerlistenZurueckButton).performClick());
            long ende=System.nanoTime()+5_000_000_000L;
            while(scenario.getState()!=Lifecycle.State.DESTROYED && System.nanoTime()<ende) Thread.sleep(20);
            assertEquals(Lifecycle.State.DESTROYED,scenario.getState());
            assertTrue(speicher.hatOffeneSynchronisierungFuerZaehlerliste(41));
        }
    }

    private void zurueck(Class<? extends Activity> typ) throws Exception {
        try (ActivityScenario<Activity> scenario = oeffnen(typ)) {
            aendern(scenario,0,"Notiz beim Zurueck");
            scenario.onActivity(a -> a.findViewById(R.id.zurueckButton).performClick());
            long ende = System.nanoTime()+5_000_000_000L;
            while (scenario.getState()!=Lifecycle.State.DESTROYED && System.nanoTime()<ende) Thread.sleep(20);
            assertEquals(Lifecycle.State.DESTROYED,scenario.getState());
        }
        speicher.close(); speicher = new LokalerInventurSpeicher(context,name);
        assertEquals(Integer.valueOf(0),speicher.ladeArtikel(41).get(0).getZaehlMenge());
        assertEquals("Notiz beim Zurueck",speicher.ladeArtikel(41).get(0).getAnmerkung());
        assertTrue(speicher.hatOffeneSynchronisierungFuerZaehlerliste(41));
    }

    private void schnellerWechsel(Class<? extends Activity> typ) throws Exception {
        speichernBegonnen = new java.util.concurrent.CountDownLatch(1);
        speichernFreigeben = new java.util.concurrent.CountDownLatch(1);
        try (ActivityScenario<Activity> scenario = oeffnen(typ)) {
            aendern(scenario,1,"Erste Notiz");
            scenario.onActivity(a -> aufrufen(a,"speichereZaehlmengenLokal"));
            assertTrue(speichernBegonnen.await(2,java.util.concurrent.TimeUnit.SECONDS));
            aendern(scenario,2,"Zweite Notiz");
            scenario.onActivity(a -> {
                try {
                    Method m=a.getClass().getDeclaredMethod("pruefeUngespeicherteAenderungenVorDemBlattwechsel",int.class);
                    m.setAccessible(true);m.invoke(a,1);
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                assertEquals(41,feld(a,"zaehlerlistenId"));
            });
            speichernFreigeben.countDown();
            warte(scenario,a -> (Integer) feld(a,"zaehlerlistenId")==42
                    && !(Boolean) feld(a,"ignoriertAenderungenBeimInitialisieren"));
            assertEquals(Integer.valueOf(2),speicher.ladeArtikel(41).get(0).getZaehlMenge());
            assertEquals("Zweite Notiz",speicher.ladeArtikel(41).get(0).getAnmerkung());
            assertNull(speicher.ladeArtikel(42).get(0).getZaehlMenge());
            assertTrue(speicher.hatOffeneSynchronisierungFuerZaehlerliste(41));
            assertFalse(speicher.hatOffeneSynchronisierungFuerZaehlerliste(42));
        } finally { speichernFreigeben.countDown(); }
    }

    private void sqliteFehler(Class<? extends Activity> typ) throws Exception {
        try (ActivityScenario<Activity> scenario = oeffnen(typ)) {
            speicher.getWritableDatabase().execSQL("CREATE TRIGGER test_fehler BEFORE INSERT ON sync_warteschlange "
                    + "BEGIN SELECT RAISE(ABORT,'Testfehler'); END");
            aendern(scenario,7,"Nicht gespeichert");
            scenario.onActivity(a -> a.findViewById(R.id.zurueckButton).performClick());
            warte(scenario,a -> !(Boolean) feld(a,"autoSaveLaeuft"));
            assertEquals(Lifecycle.State.RESUMED,scenario.getState());
            assertNull(speicher.ladeArtikel(41).get(0).getZaehlMenge());
            assertEquals("",speicher.ladeArtikel(41).get(0).getAnmerkung());
            assertFalse(speicher.hatOffeneSynchronisierung());
            speicher.getWritableDatabase().execSQL("DROP TRIGGER test_fehler");
        }
    }

    private void ohneSchreiber(Class<? extends Activity> typ) throws Exception {
        try (ActivityScenario<Activity> scenario = oeffnen(typ)) {
            scenario.onActivity(a -> ((android.widget.EditText)a.findViewById(R.id.schreiberEditText)).setText(""));
            aendern(scenario,0,"Ohne Schreiber lokal erhalten");
            scenario.recreate();
            assertEquals(Integer.valueOf(0),speicher.ladeArtikel(41).get(0).getZaehlMenge());
            assertEquals("Ohne Schreiber lokal erhalten",speicher.ladeArtikel(41).get(0).getAnmerkung());
            assertTrue(speicher.hatOffeneSynchronisierungFuerZaehlerliste(41));
        }
    }

    private void warte(ActivityScenario<Activity> scenario, java.util.function.Predicate<Activity> pruefung) throws Exception {
        long ende=System.nanoTime()+5_000_000_000L;
        while(System.nanoTime()<ende) {
            boolean[] fertig={false};scenario.onActivity(a -> fertig[0]=pruefung.test(a));
            if(fertig[0])return;
            Thread.sleep(20);
        }
        fail("Speichervorgang wurde nicht rechtzeitig abgeschlossen");
    }

    private void hintergrund(Class<? extends Activity> typ) throws Exception {
        try (ActivityScenario<Activity> scenario = oeffnen(typ)) {
            aendern(scenario, 7, "Notiz vor Verlassen");
            scenario.recreate();
            assertEquals(Integer.valueOf(7), speicher.ladeArtikel(41).get(0).getZaehlMenge());
            assertEquals("Notiz vor Verlassen", speicher.ladeArtikel(41).get(0).getAnmerkung());
            assertTrue(speicher.hatOffeneSynchronisierungFuerZaehlerliste(41));
        }
    }

    private ActivityScenario<Activity> oeffnen(Class<? extends Activity> typ) throws Exception {
        ActivityScenario<Activity> scenario = ActivityScenario.launch(new Intent(context, typ)
                .putExtra(ArtikeluebersichtActivity.EXTRA_ZAEHLERLISTEN_ID, 41)
                .putExtra(ArtikeluebersichtActivity.EXTRA_ZAEHLERLISTEN_BEZEICHNUNG, "Testgruppe"));
        long ende = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < ende) {
            boolean[] fertig = {false};
            scenario.onActivity(a -> fertig[0] = !(Boolean) feld(a, "ignoriertAenderungenBeimInitialisieren"));
            if (fertig[0]) return scenario;
            Thread.sleep(20);
        }
        scenario.close();
        throw new AssertionError("Artikelansicht wurde nicht geladen");
    }

    @SuppressWarnings("unchecked")
    private void aendern(ActivityScenario<Activity> scenario, int menge, String notiz) {
        scenario.onActivity(a -> {
            List<Artikel> artikel = (List<Artikel>) feld(a, "artikelListe");
            artikel.get(0).setZaehlMenge(menge);
            artikel.get(0).setAnmerkung(notiz);
            aufrufen(a, "verarbeiteArtikelAenderung");
        });
    }

    private static Object feld(Activity a, String name) {
        try { Field f = a.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(a); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void aufrufen(Activity a, String name) {
        try { Method m = a.getClass().getDeclaredMethod(name); m.setAccessible(true); m.invoke(a); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static class KeinSql implements InventurRemoteDataSource {
        @Override public boolean istSchreibgeschuetzt() { return true; }
        @Override public void pruefeVerbindung() { throw new AssertionError("Keine SQL-Verbindung im Test"); }
        @Override public List<ZaehlerlisteDto> ladeZaehlerlisten() { throw new AssertionError("Keine SQL-Abfrage im Test"); }
        @Override public List<ArtikelDto> ladeArtikel(int id) { throw new AssertionError("Keine SQL-Abfrage im Test"); }
        @Override public ZaehlmengenSpeicherAntwort speichereZaehlmengen(ZaehlmengenSpeicherAnfrage anfrage) { throw new AssertionError("SQL gesperrt"); }
        @Override public void markiereZaehlerlisteAlsAbgeschlossen(int id) { throw new AssertionError("SQL gesperrt"); }
        @Override public String holeLetzteDiagnose() { return ""; }
    }
}
