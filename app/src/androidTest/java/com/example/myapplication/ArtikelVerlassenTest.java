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
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;

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
    private volatile java.util.concurrent.CountDownLatch syncBegonnen;
    private volatile java.util.concurrent.CountDownLatch syncFreigeben;
    private volatile int synchronisierungen;
    private volatile boolean testServerBestaetigt;
    private volatile boolean abschlussAngefordert;
    private boolean testMengenwegAktiv;
    private boolean testVorbereitungFehlgeschlagen;
    private ZaehlmengenSpeicherAntwort testRueckmeldung;

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
            @Override public boolean istSqlMengenabgleichVerfuegbar(){return testMengenwegAktiv;}
            @Override public ZaehlmengenSpeicherAntwort synchronisiereBeimVerlassen(int id, boolean abschliessen) {
                synchronisierungen++;
                if(testRueckmeldung!=null)return testRueckmeldung;
                abschlussAngefordert=abschliessen;
                if (syncFreigeben != null) {
                    syncBegonnen.countDown();
                    try {
                        if (!syncFreigeben.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Testfreigabe fehlt");
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                }
                if (!testServerBestaetigt) {
                    if(testVorbereitungFehlgeschlagen) return new ZaehlmengenSpeicherAntwort(false,"SQL-Status 08001",null)
                            .mitMengenbericht(0,1,0,0,java.util.Collections.singletonMap(1,"Die Verbindung konnte nicht hergestellt werden."),true);
                    return super.synchronisiereBeimVerlassen(id,abschliessen);
                }
                // Der UI-Fake bestaetigt nur Mengen; Metadaten und Listenauftrag bleiben erhalten.
                if (abschliessen) speicher.markiereZaehlerlisteAlsAbgeschlossen(id);
                android.database.sqlite.SQLiteDatabase db=speicher.getWritableDatabase();
                db.beginTransaction();
                try {
                    db.execSQL("UPDATE artikel SET menge_lokal_geaendert=0 WHERE zaehlerlisten_id=?",new Object[]{id});
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
                return new ZaehlmengenSpeicherAntwort(true,"Testserver bestätigt",null);
            }
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

    @Test public void originalerMengendialogSpeichertNullmengeLokal() throws Exception {
        try (ActivityScenario<Activity> scenario = oeffnen(ArtikeluebersichtActivity.class)) {
            onView(withId(R.id.zaehlMengeEditText)).perform(click());
            onView(isAssignableFrom(android.widget.EditText.class)).inRoot(isDialog())
                    .perform(replaceText("0"), closeSoftKeyboard());
            onView(withText(R.string.anmerkung_dialog_speichern)).inRoot(isDialog()).perform(click());
            scenario.moveToState(Lifecycle.State.CREATED);
            assertEquals(Integer.valueOf(0), speicher.ladeArtikel(41).get(0).getZaehlMenge());
            assertTrue(speicher.hatOffeneSynchronisierungFuerZaehlerliste(41));
        }
    }

    @Test public void detaildialogSpeichertMengeUndNotizLokal() throws Exception {
        try (ActivityScenario<Activity> scenario = oeffnen(ArtikeluebersichtV2Activity.class)) {
            onView(withId(R.id.artikelCardView)).perform(click());
            onView(withId(R.id.detailZaehlMengeEditText)).perform(replaceText("0"), closeSoftKeyboard());
            onView(withId(R.id.detailAnmerkungEditText)).perform(replaceText("Testnotiz"), closeSoftKeyboard());
            onView(withId(R.id.detailSpeichernButton)).perform(click());
            scenario.moveToState(Lifecycle.State.CREATED);
            Artikel artikel = speicher.ladeArtikel(41).get(0);
            assertEquals(Integer.valueOf(0), artikel.getZaehlMenge());
            assertEquals("Testnotiz", artikel.getAnmerkung());
            assertTrue(speicher.hatOffeneSynchronisierungFuerZaehlerliste(41));
        }
    }
    @Test public void version1SpeichertBeimSofortigenZurueck() throws Exception { zurueck(ArtikeluebersichtActivity.class); }
    @Test public void version2SpeichertBeimSofortigenZurueck() throws Exception { zurueck(ArtikeluebersichtV2Activity.class); }
    @Test public void version1BlattwechselWartetAufDieLetzteAenderung() throws Exception { schnellerWechsel(ArtikeluebersichtActivity.class); }
    @Test public void version2BlattwechselWartetAufDieLetzteAenderung() throws Exception { schnellerWechsel(ArtikeluebersichtV2Activity.class); }
    @Test public void version1BleibtBeiSqliteFehlerOffen() throws Exception { sqliteFehler(ArtikeluebersichtActivity.class); }
    @Test public void version2BleibtBeiSqliteFehlerOffen() throws Exception { sqliteFehler(ArtikeluebersichtV2Activity.class); }
    @Test public void version1SichertAuchOhneSchreibernamenLokal() throws Exception { ohneSchreiber(ArtikeluebersichtActivity.class); }
    @Test public void version2SichertAuchOhneSchreibernamenLokal() throws Exception { ohneSchreiber(ArtikeluebersichtV2Activity.class); }

    @Test public void blattlisteBleibtMitGesperrterUebertragungOffen() throws Exception {
        List<Artikel> artikel=speicher.ladeArtikel(41);artikel.get(0).setZaehlMenge(3);
        speicher.speichereZaehlmengen(41,null,null,artikel);
        try (ActivityScenario<Activity> scenario = ActivityScenario.launch(new Intent(context,ZaehlerlistenActivity.class)
                .putExtra(ZaehlerlistenActivity.EXTRA_AUSGEWAEHLTE_ZAEHLERLISTEN_ID,41))) {
            scenario.onActivity(a -> a.findViewById(R.id.zaehlerlistenZurueckButton).performClick());
            assertEquals(Lifecycle.State.RESUMED,scenario.getState());
            assertTrue(speicher.hatOffeneSynchronisierungFuerZaehlerliste(41));
        }
    }

    private void zurueck(Class<? extends Activity> typ) throws Exception {
        try (ActivityScenario<Activity> scenario = oeffnen(typ)) {
            aendern(scenario,0,"Notiz beim Zurueck");
            scenario.onActivity(a -> a.findViewById(R.id.zurueckButton).performClick());
            entscheide(scenario,false);
            warte(scenario,a -> synchronisierungen==1 && !(Boolean)feld(a,"austrittLaeuft"));
            assertEquals(Lifecycle.State.RESUMED,scenario.getState());
            assertFalse(speicher.ladeZaehlerlisteFuerId(41).istAbgeschlossen());
        }
        speicher.close(); speicher = new LokalerInventurSpeicher(context,name);
        assertEquals(Integer.valueOf(0),speicher.ladeArtikel(41).get(0).getZaehlMenge());
        assertEquals("Notiz beim Zurueck",speicher.ladeArtikel(41).get(0).getAnmerkung());
        assertTrue(speicher.hatOffeneSynchronisierungFuerZaehlerliste(41));
    }

    private void schnellerWechsel(Class<? extends Activity> typ) throws Exception {
        testServerBestaetigt=true;
        speichernBegonnen = new java.util.concurrent.CountDownLatch(1);
        speichernFreigeben = new java.util.concurrent.CountDownLatch(1);
        try (ActivityScenario<Activity> scenario = oeffnen(typ)) {
            aendern(scenario,1,"Erste Notiz");
            scenario.onActivity(a -> aufrufen(a,"speichereZaehlmengenLokal"));
            assertTrue(speichernBegonnen.await(2,java.util.concurrent.TimeUnit.SECONDS));
            aendern(scenario,2,"Zweite Notiz");
            scenario.onActivity(a -> {
                try {
                    Method m=ArtikeluebersichtActivity.class.getDeclaredMethod("pruefeUngespeicherteAenderungenVorDemBlattwechsel",int.class);
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
            assertFalse(speicher.hatOffeneMengensynchronisierung(41));
            assertTrue(speicher.hatOffeneSynchronisierungFuerZaehlerliste(41));
            assertFalse(speicher.hatOffeneSynchronisierungFuerZaehlerliste(42));
            assertEquals(1,synchronisierungen);assertFalse(abschlussAngefordert);
        } finally { speichernFreigeben.countDown(); }
    }

    private void sqliteFehler(Class<? extends Activity> typ) throws Exception {
        try (ActivityScenario<Activity> scenario = oeffnen(typ)) {
            speicher.getWritableDatabase().execSQL("CREATE TRIGGER test_fehler BEFORE INSERT ON sync_warteschlange "
                    + "BEGIN SELECT RAISE(ABORT,'Testfehler'); END");
            aendern(scenario,7,"Nicht gespeichert");
            scenario.onActivity(a -> a.findViewById(R.id.zurueckButton).performClick());
            entscheide(scenario,false);
            warte(scenario,a -> !(Boolean) feld(a,"autoSaveLaeuft") && !(Boolean)feld(a,"austrittLaeuft"));
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
            boolean[] fertig={false};scenario.onActivity(a -> { bestaetigeErfolg(a); fertig[0]=pruefung.test(a); });
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
            assertEquals(0,synchronisierungen);
            scenario.onActivity(a -> assertNull(feld(a,"austrittsRueckmeldung")));
        }
    }

    private void entscheide(ActivityScenario<Activity> scenario, boolean abschliessen) {
        scenario.onActivity(a -> {
            androidx.appcompat.app.AlertDialog dialog=(androidx.appcompat.app.AlertDialog)feld(a,"abschlussDialog");
            assertNotNull(dialog);
            dialog.getButton(abschliessen ? android.content.DialogInterface.BUTTON_POSITIVE
                    : android.content.DialogInterface.BUTTON_NEGATIVE).performClick();
        });
    }

    private void leereKopfdaten(int blatt) {
        speicher.getWritableDatabase().execSQL("UPDATE zaehlerliste SET zaehler_name='',schreiber_name='' WHERE id=?",new Object[]{blatt});
        speicher.getWritableDatabase().delete("zaehlerlisten_status","zaehlerlisten_id=?",new String[]{String.valueOf(blatt)});
    }
    private long kopfZahl(String spalte) {
        try(android.database.Cursor c=speicher.getReadableDatabase().rawQuery("SELECT "+spalte+" FROM zaehlerliste WHERE id=41",null)) {
            assertTrue(c.moveToFirst());return c.getLong(0);
        }
    }
    private void pruefeLeerenSauberenKopf() {
        Zaehlerliste kopf=speicher.ladeZaehlerlisteFuerId(41);
        assertEquals("",kopf.getGespeicherterZaehlerName());assertEquals("",kopf.getGespeicherterSchreiberName());
        assertEquals(0,kopfZahl("kopf_lokal_geaendert+abschluss_lokal_geaendert+lokale_revision"));
        assertFalse(kopf.istAbgeschlossen());
    }
    @Test public void anzeigevorgabenErzeugenBeimOffenLassenImOriginalKeinenKopfauftrag() throws Exception {
        unveraenderteAnzeigeErzeugtKeinenKopf(ArtikeluebersichtActivity.class);
    }
    @Test public void anzeigevorgabenErzeugenBeimOffenLassenInV2KeinenKopfauftrag() throws Exception {
        unveraenderteAnzeigeErzeugtKeinenKopf(ArtikeluebersichtV2Activity.class);
    }
    private void unveraenderteAnzeigeErzeugtKeinenKopf(Class<? extends Activity> typ) throws Exception {
        leereKopfdaten(41);
        try(ActivityScenario<Activity> scenario=oeffnen(typ)) {
            scenario.onActivity(a -> a.findViewById(R.id.zurueckButton).performClick());
            entscheide(scenario,false);warteAufVerlassen(scenario);
        }
        pruefeLeerenSauberenKopf();assertFalse(speicher.hatUnbestaetigteLokaleAenderungen());
    }
    @Test public void mengenSpeichernMitAnzeigevorgabenMarkiertNurMenge() throws Exception {
        leereKopfdaten(41);
        try(ActivityScenario<Activity> scenario=oeffnen(ArtikeluebersichtActivity.class)) {
            aendern(scenario,0,"");
            scenario.onActivity(a -> aufrufen(a,"speichereZaehlmengenLokal"));
            warte(scenario,a -> !(Boolean)feld(a,"autoSaveLaeuft"));
            pruefeLeerenSauberenKopf();
            assertEquals(Integer.valueOf(0),speicher.ladeArtikel(41).get(0).getZaehlMenge());
            assertTrue(speicher.hatOffeneSynchronisierungFuerZaehlerliste(41));
            scenario.onActivity(a -> a.findViewById(R.id.zurueckButton).performClick());entscheide(scenario,false);
            warte(scenario,a -> synchronisierungen==1 && !(Boolean)feld(a,"austrittLaeuft"));
            pruefeLeerenSauberenKopf();
        }
    }
    @Test public void bestaetigteMengeUndErneutesOeffnenErzeugenKeineKopfOderAbschlussaenderung() throws Exception {
        leereKopfdaten(41);testServerBestaetigt=true;
        try(ActivityScenario<Activity> scenario=oeffnen(ArtikeluebersichtActivity.class)) {
            aendern(scenario,0,"");scenario.onActivity(a -> aufrufen(a,"speichereZaehlmengenLokal"));
            warte(scenario,a -> !(Boolean)feld(a,"autoSaveLaeuft"));pruefeLeerenSauberenKopf();
            scenario.onActivity(a -> a.findViewById(R.id.zurueckButton).performClick());entscheide(scenario,false);
            warteAufVerlassen(scenario);
        }
        testServerBestaetigt=false;
        try(ActivityScenario<Activity> scenario=oeffnen(ArtikeluebersichtV2Activity.class)) {
            scenario.onActivity(a -> a.findViewById(R.id.zurueckButton).performClick());entscheide(scenario,false);
            warteAufVerlassen(scenario);
        }
        pruefeLeerenSauberenKopf();assertFalse(speicher.hatOffeneMengensynchronisierung(41));
        try(android.database.Cursor c=speicher.getReadableDatabase().rawQuery("SELECT lokale_revision FROM artikel WHERE zaehlerlisten_id=41",null)) {
            assertTrue(c.moveToFirst());assertEquals(1,c.getLong(0));
        }
    }
    @Test public void zaehlerUndSchreiberBleibenFestUndErzeugenKeineKopfaenderung() throws Exception {
        leereKopfdaten(41);
        try(ActivityScenario<Activity> scenario=oeffnen(ArtikeluebersichtActivity.class)) {
            scenario.onActivity(a -> {
                for(int id:new int[]{R.id.zaehlerEditText,R.id.schreiberEditText,
                        R.id.zaehlerVertretungButton,R.id.schreiberVertretungButton}) {
                    assertFalse(a.findViewById(id).isEnabled());
                }
                aufrufen(a,"speichereZaehlmengenLokal");
            });
            warte(scenario,a -> !(Boolean)feld(a,"autoSaveLaeuft"));
            pruefeLeerenSauberenKopf();
        }
    }
    @Test public void hintergrundspeichernMitAnzeigevorgabenLaesstKopfUnveraendert() throws Exception {
        leereKopfdaten(41);
        try(ActivityScenario<Activity> scenario=oeffnen(ArtikeluebersichtV2Activity.class)) {
            aendern(scenario,3,"");scenario.recreate();
            assertEquals(Integer.valueOf(3),speicher.ladeArtikel(41).get(0).getZaehlMenge());
            pruefeLeerenSauberenKopf();assertEquals(0,synchronisierungen);
        }
    }

    private static void bestaetigeErfolg(Activity a) {
        androidx.appcompat.app.AlertDialog dialog=(androidx.appcompat.app.AlertDialog)feld(a,"austrittsRueckmeldung");
        if(dialog!=null && dialog.isShowing() && (Boolean)feld(a,"austrittLaeuft"))
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick();
    }

    private void warteAufVerlassen(ActivityScenario<Activity> scenario) throws Exception {
        long ende=System.nanoTime()+5_000_000_000L;
        while(System.nanoTime()<ende) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            if(scenario.getState()==Lifecycle.State.DESTROYED)break;
            boolean[] geklickt={false};
            try {
            scenario.onActivity(a -> {
                androidx.appcompat.app.AlertDialog d=(androidx.appcompat.app.AlertDialog)feld(a,"austrittsRueckmeldung");
                if(d!=null && d.isShowing() && (Boolean)feld(a,"austrittLaeuft")) {
                    bestaetigeErfolg(a);geklickt[0]=true;
                }
            });
            } catch(NullPointerException e) {
                // ActivityScenario kann zwischen Zustandsabfrage und UI-Aufruf zerstoert werden.
                if(!"Cannot run onActivity since Activity has been destroyed already".equals(e.getMessage()))throw e;
            }
            if(geklickt[0]) {
                while(scenario.getState()!=Lifecycle.State.DESTROYED && System.nanoTime()<ende)Thread.sleep(20);
                break;
            }
            Thread.sleep(20);
        }
        String[] zustand={""};
        if(scenario.getState()!=Lifecycle.State.DESTROYED)scenario.onActivity(a -> zustand[0]=
                "; Austritt="+feld(a,"austrittLaeuft")+"; Speichern="+feld(a,"autoSaveLaeuft")
                +"; Fortsetzung="+(feld(a,"nachLokalemSpeichern")!=null));
        assertEquals("Synchronisierungsaufrufe: "+synchronisierungen+zustand[0], Lifecycle.State.DESTROYED,scenario.getState());
    }

    @Test public void offenLassenSynchronisiertOriginalOhneAbschluss() throws Exception { erfolgreicherAustritt(ArtikeluebersichtActivity.class,false); }
    @Test public void offenLassenSynchronisiertV2OhneAbschluss() throws Exception { erfolgreicherAustritt(ArtikeluebersichtV2Activity.class,false); }
    @Test public void ausdruecklicherAbschlussVerwendetDenselbenAblauf() throws Exception { erfolgreicherAustritt(ArtikeluebersichtActivity.class,true); }
    @Test public void ausdruecklicherAbschlussV2VerwendetDenselbenAblauf() throws Exception { erfolgreicherAustritt(ArtikeluebersichtV2Activity.class,true); }

    private void erfolgreicherAustritt(Class<? extends Activity> typ, boolean abschliessen) throws Exception {
        testServerBestaetigt=true;
        try(ActivityScenario<Activity> scenario=oeffnen(typ)) {
            aendern(scenario,0,"");
            scenario.onActivity(a -> {
                a.findViewById(R.id.zurueckButton).performClick();
                a.findViewById(R.id.zurueckButton).performClick();
            });
            entscheide(scenario,abschliessen);
            warteAufVerlassen(scenario);
        }
        assertEquals(1,synchronisierungen);assertEquals(abschliessen,abschlussAngefordert);
        assertEquals(abschliessen,speicher.ladeZaehlerlisteFuerId(41).istAbgeschlossen());
        assertEquals(Integer.valueOf(0),speicher.ladeArtikel(41).get(0).getZaehlMenge());
        assertFalse(speicher.hatOffeneMengensynchronisierung(41));
        assertEquals(abschliessen?1:0,kopfZahl("abschluss_lokal_geaendert"));
        assertTrue(speicher.hatOffeneSynchronisierungFuerZaehlerliste(41));
    }

    @Test public void historischeMetadatenUndListenauftragErlaubenAustrittOhneQuittierung() throws Exception {
        speicher.getWritableDatabase().execSQL("UPDATE zaehlerliste SET kopf_lokal_geaendert=1,abschluss_lokal_geaendert=1,lokale_revision=8 WHERE id=41");
        speicher.getWritableDatabase().execSQL("UPDATE artikel SET anmerkung='Lokale Notiz',notiz_lokal_geaendert=1 WHERE zaehlerlisten_id=41");
        speicher.merkeZaehlerlisteFuerSynchronisierung(41);
        try(ActivityScenario<Activity> scenario=oeffnen(ArtikeluebersichtActivity.class)) {
            scenario.onActivity(a -> a.findViewById(R.id.zurueckButton).performClick());
            entscheide(scenario,false);warteAufVerlassen(scenario);
        }
        assertFalse(speicher.hatOffeneMengensynchronisierung(41));
        assertTrue(speicher.hatUnbestaetigteLokaleAenderungen());
        assertEquals(1,kopfZahl("kopf_lokal_geaendert"));assertEquals(1,kopfZahl("abschluss_lokal_geaendert"));
        assertEquals(8,kopfZahl("lokale_revision"));
        assertEquals("Lokale Notiz",speicher.ladeArtikel(41).get(0).getAnmerkung());
        assertFalse(speicher.ladeZaehlerlisteFuerId(41).istAbgeschlossen());
        try(ActivityScenario<ZaehlerlistenActivity> scenario=ActivityScenario.launch(new Intent(context,ZaehlerlistenActivity.class)
                .putExtra(ZaehlerlistenActivity.EXTRA_AUSGEWAEHLTE_ZAEHLERLISTEN_ID,41))) {
            scenario.onActivity(a -> a.findViewById(R.id.zaehlerlistenZurueckButton).performClick());
            long ende=System.nanoTime()+5_000_000_000L;
            while(scenario.getState()!=Lifecycle.State.DESTROYED && System.nanoTime()<ende)Thread.sleep(20);
            assertEquals(Lifecycle.State.DESTROYED,scenario.getState());
        }
        assertTrue(speicher.hatOffeneSynchronisierungFuerZaehlerliste(41));
    }

    @Test public void unveraenderteOffeneListeBrauchtKeinenSqlAufruf() throws Exception {
        try(ActivityScenario<Activity> scenario=oeffnen(ArtikeluebersichtActivity.class)) {
            scenario.onActivity(a -> a.findViewById(R.id.zurueckButton).performClick());
            entscheide(scenario,false);warteAufVerlassen(scenario);
        }
        assertEquals(1,synchronisierungen);assertFalse(speicher.ladeZaehlerlisteFuerId(41).istAbgeschlossen());
    }

    @Test public void bestaetigteAbgeschlosseneListeVerlaesstOhneNeueAbschlussaenderung() throws Exception {
        speicher.getWritableDatabase().execSQL("UPDATE zaehlerliste SET abgeschlossen=1 WHERE id=41");
        try(ActivityScenario<Activity> scenario=oeffnen(ArtikeluebersichtV2Activity.class)) {
            scenario.onActivity(a -> a.findViewById(R.id.zurueckButton).performClick());
            warteAufVerlassen(scenario);
        }
        assertEquals(1,synchronisierungen);assertFalse(abschlussAngefordert);
        assertFalse(speicher.hatUnbestaetigteLokaleAenderungen());
    }

    @Test public void doppelteZurueckAktionStartetWaehrendSyncKeinenZweitenAuftrag() throws Exception {
        testServerBestaetigt=true;syncBegonnen=new java.util.concurrent.CountDownLatch(1);
        syncFreigeben=new java.util.concurrent.CountDownLatch(1);
        try(ActivityScenario<Activity> scenario=oeffnen(ArtikeluebersichtActivity.class)) {
            aendern(scenario,3,"");
            scenario.onActivity(a -> a.findViewById(R.id.zurueckButton).performClick());entscheide(scenario,false);
            assertTrue(syncBegonnen.await(2,java.util.concurrent.TimeUnit.SECONDS));
            scenario.onActivity(a -> {
                a.findViewById(R.id.zurueckButton).performClick();
                ((ArtikeluebersichtActivity)a).getOnBackPressedDispatcher().onBackPressed();
                assertNotNull(feld(a,"austrittsFortschritt"));assertTrue((Boolean)feld(a,"austrittLaeuft"));
            });
            assertEquals(1,synchronisierungen);syncFreigeben.countDown();warteAufVerlassen(scenario);
        } finally {syncFreigeben.countDown();}
    }

    @Test public void fehlgeschlagenerAustrittBleibtOffenUndKannErneutVersuchtWerden() throws Exception {
        testMengenwegAktiv=true;testVorbereitungFehlgeschlagen=true;
        try(ActivityScenario<Activity> scenario=oeffnen(ArtikeluebersichtActivity.class)) {
            aendern(scenario,4,"");
            scenario.onActivity(a -> a.findViewById(R.id.zurueckButton).performClick());entscheide(scenario,false);
            warte(scenario,a -> synchronisierungen==1 && !(Boolean)feld(a,"austrittLaeuft"));
            assertTrue(speicher.hatUnbestaetigteLokaleAenderungen());testServerBestaetigt=true;
            onView(withText(R.string.austritt_sync_wiederholen)).inRoot(isDialog()).perform(new androidx.test.espresso.ViewAction() {
                @Override public org.hamcrest.Matcher<android.view.View> getConstraints(){return isDisplayed();}
                @Override public String getDescription(){return "Wiederholungsbutton auf dem UI-Thread betätigen";}
                @Override public void perform(androidx.test.espresso.UiController controller, android.view.View view){
                    assertTrue(view.performClick());controller.loopMainThreadUntilIdle();
                }
            });
            warteAufVerlassen(scenario);
        }
        assertEquals(2,synchronisierungen);assertFalse(abschlussAngefordert);
        assertFalse(speicher.hatOffeneMengensynchronisierung(41));
    }

    @Test public void gesperrterSqlModusBietetKeineWirkungsloseUebertragungAn() throws Exception {
        try(ActivityScenario<Activity> scenario=oeffnen(ArtikeluebersichtActivity.class)) {
            aendern(scenario,4,"");
            scenario.onActivity(a -> a.findViewById(R.id.zurueckButton).performClick());entscheide(scenario,false);
            warte(scenario,a -> synchronisierungen==1 && !(Boolean)feld(a,"austrittLaeuft"));
            onView(withText(R.string.austritt_sync_wiederholen)).inRoot(isDialog()).check(doesNotExist());
            assertTrue(speicher.hatUnbestaetigteLokaleAenderungen());
        }
    }

    @Test public void fehlgeschlagenerAbschlussSperrtMengenbearbeitungImOriginal() throws Exception {
        lokalerAbschlussBleibtGesperrt(ArtikeluebersichtActivity.class);
    }
    @Test public void fehlgeschlagenerAbschlussSperrtMengenbearbeitungInV2() throws Exception {
        lokalerAbschlussBleibtGesperrt(ArtikeluebersichtV2Activity.class);
    }
    private void lokalerAbschlussBleibtGesperrt(Class<? extends Activity> typ) throws Exception {
        try(ActivityScenario<Activity> scenario=oeffnen(typ)) {
            aendern(scenario,4,"");
            scenario.onActivity(a -> a.findViewById(R.id.zurueckButton).performClick());entscheide(scenario,true);
            warte(scenario,a -> synchronisierungen==1 && !(Boolean)feld(a,"austrittLaeuft"));
            assertEquals(Lifecycle.State.RESUMED,scenario.getState());
            assertTrue(speicher.ladeZaehlerlisteFuerId(41).istAbgeschlossen());
            scenario.onActivity(a -> assertTrue(((ArtikeluebersichtActivity)a).istBearbeitungGesperrt()));
            assertTrue(speicher.hatUnbestaetigteLokaleAenderungen());
            assertEquals(Integer.valueOf(4),speicher.ladeArtikel(41).get(0).getZaehlMenge());
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
        try { Field f = ArtikeluebersichtActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(a); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void aufrufen(Activity a, String name) {
        try {
            boolean speichern = name.equals("speichereZaehlmengenLokal");
            Method methode = speichern
                    ? ArtikeluebersichtActivity.class.getDeclaredMethod(name, Runnable.class)
                    : ArtikeluebersichtActivity.class.getDeclaredMethod(name);
            methode.setAccessible(true);
            if (speichern) methode.invoke(a, (Runnable) null); else methode.invoke(a);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
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
    private void warteAufRueckmeldung(ActivityScenario<Activity> scenario) throws Exception {
        long ende=System.nanoTime()+5_000_000_000L;
        while(System.nanoTime()<ende) {
            boolean[] sichtbar={false};scenario.onActivity(a -> {
                androidx.appcompat.app.AlertDialog d=(androidx.appcompat.app.AlertDialog)feld(a,"austrittsRueckmeldung");
                sichtbar[0]=d!=null && d.isShowing();
            });
            if(sichtbar[0])return;
            Thread.sleep(20);
        }
        fail("Rueckmeldung fehlt");
    }
    @Test public void erfolgsdialogWartetVorNavigationAufBestaetigung() throws Exception {
        testRueckmeldung=new ZaehlmengenSpeicherAntwort(true,"Testbestaetigung",null)
                .mitMengenbericht(10,10,10,0,Collections.emptyMap(),false);
        try(ActivityScenario<Activity> scenario=oeffnen(ArtikeluebersichtActivity.class)) {
            scenario.onActivity(a -> a.findViewById(R.id.zurueckButton).performClick());entscheide(scenario,false);
            warteAufRueckmeldung(scenario);
            onView(withText("10/10 \u00fcbernommen")).inRoot(isDialog()).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()));
            assertEquals(Lifecycle.State.RESUMED,scenario.getState());assertEquals(1,synchronisierungen);
            scenario.onActivity(a -> assertTrue((Boolean)feld(a,"austrittLaeuft")));
            warteAufVerlassen(scenario);
        }
    }
    @Test public void fehlerdialogTrenntBenutzergruendeUndItDetailsOhneUnsicherenRetry() throws Exception {
        java.util.Map<Integer,String> gruende=new java.util.TreeMap<>();
        gruende.put(2,"Der Eintrag wurde zwischenzeitlich ver\u00e4ndert.");
        gruende.put(4,"Die Speicherung konnte nicht best\u00e4tigt werden.");
        testRueckmeldung=new ZaehlmengenSpeicherAntwort(false,"Position 2: SQL-Konflikt; Position 4: SQL-Code 0, Status 08S01",null)
                .mitMengenbericht(5,10,5,0,gruende,false);
        try(ActivityScenario<Activity> scenario=oeffnen(ArtikeluebersichtV2Activity.class)) {
            aendern(scenario,4,"");
            scenario.onActivity(a -> a.findViewById(R.id.zurueckButton).performClick());entscheide(scenario,false);
            warteAufRueckmeldung(scenario);
            onView(withText(R.string.austritt_sync_wiederholen)).inRoot(isDialog()).check(doesNotExist());
            scenario.onActivity(a -> {
                androidx.appcompat.app.AlertDialog d=(androidx.appcompat.app.AlertDialog)feld(a,"austrittsRueckmeldung");
                String text=((android.widget.TextView)d.findViewById(android.R.id.message)).getText().toString();
                assertTrue(text.startsWith("5/10 \u00fcbernommen"));assertFalse(text.contains("SQL"));
                assertEquals("!",d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).getText().toString());
                d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).performClick();
            });
            onView(withSubstring("Status 08S01")).inRoot(isDialog()).check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()));
            scenario.onActivity(a -> ((androidx.appcompat.app.AlertDialog)feld(a,"austrittsDetails"))
                    .getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick());
            warteAufRueckmeldung(scenario);assertEquals(Lifecycle.State.RESUMED,scenario.getState());
            assertTrue(speicher.hatOffeneMengensynchronisierung(41));assertEquals(1,synchronisierungen);
        }
    }

    @Test public void systemZurueckZeigtBeiUnveraenderterListeKeineSchreibbestaetigung() throws Exception {
        try(ActivityScenario<Activity> scenario=oeffnen(ArtikeluebersichtActivity.class)) {
            scenario.onActivity(a -> ((ArtikeluebersichtActivity)a).getOnBackPressedDispatcher().onBackPressed());
            entscheide(scenario,false);warteAufRueckmeldung(scenario);
            onView(withText("Keine neuen \u00c4nderungen vorhanden.")).inRoot(isDialog())
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(isDisplayed()));
            assertEquals(1,synchronisierungen);
            warteAufVerlassen(scenario);
        }
    }

}
