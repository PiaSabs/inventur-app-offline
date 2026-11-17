package com.example.myapplication;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ListenuebersichtRepositoryTest {
    private Context context;
    private String name;
    private LokalerInventurSpeicher speicher;
    private KopfQuelle quelle;
    private ArtikelRepository repository;

    @Before public void vorbereiten() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        name = "listenkoepfe_" + UUID.randomUUID() + ".db";
        speicher = new LokalerInventurSpeicher(context, name);
        quelle = new KopfQuelle();
        repository = new ArtikelRepository(quelle, speicher);
        speicher.initialisiereZaehlerlisten(Collections.singletonList(kopf(41, "Lokal")));
        speicher.initialisiereArtikel(41, Arrays.asList(
                new ArtikelDto(1, "TEST-A", "A", "L1", 7, null, 1, null),
                new ArtikelDto(2, "TEST-B", "B", "L2", 8, 0, 1, 0)));
        List<Artikel> artikel = speicher.ladeArtikel(41);
        artikel.get(0).setAnmerkung("Offene Notiz");
        speicher.speichereZaehlmengen(41, "Testzaehler", "Testschreiber", artikel);
        speicher.markiereZaehlerlisteAlsAbgeschlossen(41);
    }

    @After public void aufraeumen() {
        speicher.close();
        context.deleteDatabase(name);
    }

    private static ZaehlerlisteDto kopf(int id, String text) {
        return new ZaehlerlisteDto(id, text, "" + id, "Test", "", true, false);
    }

    private String lokalerStand() {
        StringBuilder stand = new StringBuilder();
        for (String tabelle : new String[]{"zaehlerliste", "artikel", "zaehlerlisten_status", "sync_warteschlange"}) {
            try (Cursor c = speicher.getReadableDatabase().rawQuery("SELECT * FROM " + tabelle + " ORDER BY 1,2", null)) {
                while (c.moveToNext()) {
                    for (int i = 0; i < c.getColumnCount(); i++) {
                        String wert = c.isNull(i) ? null : c.getString(i);
                        stand.append(wert == null ? -1 : wert.length()).append(':').append(wert).append('|');
                    }
                    stand.append('\n');
                }
            }
        }
        return stand.toString();
    }

    @Test public void erstesOeffnenLiestNurKoepfeUndImportiertNichts() {
        String vorher = lokalerStand();
        quelle.listen = Collections.singletonList(kopf(42, "Neue Serverliste"));
        Listenuebersicht stand = repository.aktualisiereListenuebersicht();
        assertTrue(stand.istServerstand());
        assertEquals(2, stand.getListen().size());
        assertFalse(stand.istLokalVerfuegbar(42));
        assertNull(speicher.ladeZaehlerlisteFuerId(42));
        assertEquals(vorher, lokalerStand());
        assertEquals(1, quelle.aufrufe.get());
    }

    @Test public void erneutesOeffnenUmgehtNurDieEinmaligeLadebegrenzung() throws Exception {
        Field begrenzung = ArtikelRepository.class.getDeclaredField("remoteBestandSynchronisiert");
        begrenzung.setAccessible(true);
        begrenzung.setBoolean(repository, true);
        repository.setzeAutomatischeSynchronisierungPausiert(true);
        quelle.listen = Collections.singletonList(kopf(41, "Stand eins"));
        assertEquals("Stand eins", repository.aktualisiereListenuebersicht().getListen().get(0).getBezeichnung());
        quelle.listen = Arrays.asList(kopf(41, "Stand zwei"), kopf(42, "Neu"));
        Listenuebersicht neu = repository.aktualisiereListenuebersicht();
        assertEquals("Stand zwei", neu.getListen().get(0).getBezeichnung());
        assertEquals(2, neu.getListen().size());
        assertEquals(2, quelle.aufrufe.get());
        assertTrue(begrenzung.getBoolean(repository));
    }

    @Test public void umbenennungErhaeltMengenNotizenAbschlussRevisionUndAuftrag() {
        String vorher = lokalerStand();
        quelle.listen = Collections.singletonList(kopf(41, "Neue Bezeichnung"));
        Listenuebersicht stand = repository.aktualisiereListenuebersicht();
        assertEquals("Neue Bezeichnung", stand.getListen().get(0).getBezeichnung());
        assertTrue(stand.getListen().get(0).istAbgeschlossen());
        assertEquals(vorher, lokalerStand());
    }

    @Test public void fehlerNachTeilweisemLesenZeigtNurLokalenStand() {
        String vorher = lokalerStand();
        quelle.listen = Arrays.asList(kopf(41, "Darf nicht erscheinen"), kopf(42, "Teilstand"));
        quelle.offline = true;
        Listenuebersicht stand = repository.aktualisiereListenuebersicht();
        assertFalse(stand.istServerstand());
        assertEquals(1, stand.getListen().size());
        assertEquals("Lokal", stand.getListen().get(0).getBezeichnung());
        assertEquals(vorher, lokalerStand());
    }

    @Test public void offlineNachErfolgWirdNichtWeiterAlsAktuellerServerstandBezeichnet() {
        quelle.listen = Collections.singletonList(kopf(41, "Server"));
        assertTrue(repository.aktualisiereListenuebersicht().istServerstand());
        quelle.offline = true;
        assertFalse(repository.aktualisiereListenuebersicht().istServerstand());
        quelle.offline = false;
        quelle.listen = Collections.singletonList(kopf(41, "Wieder verbunden"));
        assertEquals("Wieder verbunden", repository.aktualisiereListenuebersicht().getListen().get(0).getBezeichnung());
        assertEquals(3, quelle.aufrufe.get());
    }

    @Test public void doppelteBlattidentitaetOderFehlendeAntwortWirdVollstaendigVerworfen() {
        String vorher = lokalerStand();
        quelle.listen = Arrays.asList(kopf(41, "A"), kopf(41, "B"));
        assertFalse(repository.aktualisiereListenuebersicht().istServerstand());
        quelle.listen = null;
        assertFalse(repository.aktualisiereListenuebersicht().istServerstand());
        assertEquals(vorher, lokalerStand());
    }

    @Test public void entfernteServerlisteBleibtLokalMitOffenemAuftragSichtbar() {
        String vorher = lokalerStand();
        quelle.listen = Collections.emptyList();
        Listenuebersicht stand = repository.aktualisiereListenuebersicht();
        assertTrue(stand.istServerstand());
        assertEquals(1, stand.getListen().size());
        assertTrue(stand.istNurLokal(41));
        assertTrue(speicher.hatOffeneSynchronisierungFuerZaehlerliste(41));
        assertEquals(vorher, lokalerStand());
    }

    @Test public void leererOfflineSpeicherWirdDurchDieUebersichtNichtInitialisiert() {
        try (LokalerInventurSpeicher leer = new LokalerInventurSpeicher(context, name + "_leer")) {
            ArtikelRepository leeresRepository = new ArtikelRepository(quelle, leer);
            quelle.listen = Collections.singletonList(kopf(42, "Nur Server"));
            assertTrue(leeresRepository.aktualisiereListenuebersicht().istServerstand());
            assertFalse(leer.hatZaehlerlisten());
            assertFalse(leer.hatOffeneSynchronisierung());
        } finally { context.deleteDatabase(name + "_leer"); }
    }

    @Test public void activityLaedtBeiErneutemAnzeigenUndKennzeichnetDieQuelle() throws Exception {
        Field container = AppContainer.class.getDeclaredField("apiArtikelRepository");
        container.setAccessible(true);
        Object vorherigesRepository = container.get(null);
        container.set(null, repository);
        String vorher = lokalerStand();
        Intent intent = new Intent(context, ZaehlerlistenActivity.class)
                .putExtra(ZaehlerlistenActivity.EXTRA_AUSGEWAEHLTE_ZAEHLERLISTEN_ID, 41)
                .putExtra(ZaehlerlistenActivity.EXTRA_AUSGEWAEHLTE_ZAEHLERLISTEN_BEZEICHNUNG, "Lokal");
        quelle.listen = Collections.singletonList(kopf(41, "Erster Stand"));
        try (ActivityScenario<ZaehlerlistenActivity> activity = ActivityScenario.launch(intent)) {
            warteAufQuelle(activity, R.string.listenquelle_server, 1);
            activity.moveToState(Lifecycle.State.CREATED);
            quelle.listen = Arrays.asList(kopf(41, "Zweiter Stand"), kopf(42, "Zweiter Stand"));
            activity.moveToState(Lifecycle.State.RESUMED);
            warteAufQuelle(activity, R.string.listenquelle_server, 2);
            assertEquals(2, quelle.aufrufe.get());
            activity.moveToState(Lifecycle.State.CREATED);
            quelle.offline = true;
            activity.moveToState(Lifecycle.State.RESUMED);
            warteAufQuelle(activity, R.string.listenquelle_lokal, 1);
            assertEquals(vorher, lokalerStand());
        } finally { container.set(null, vorherigesRepository); }
    }

    private void warteAufQuelle(ActivityScenario<ZaehlerlistenActivity> activity, int textId, int anzahl) throws Exception {
        warteAufAnzeige(activity, R.id.listenLadestatusTextView, R.id.zaehlerlistenRecyclerView, textId, anzahl);
    }

    @Test public void hauptmenueLaedtEinmalProAnzeigenUndZeigtNeueGruppen() throws Exception {
        Field container = AppContainer.class.getDeclaredField("apiArtikelRepository");
        container.setAccessible(true);
        Object vorherigesRepository = container.get(null);
        Benutzersitzung sitzung = new Benutzersitzung(context);
        String vorherigerName = sitzung.holeAngemeldetenZaehler();
        sitzung.setzeAngemeldetenZaehler("Testzaehler");
        container.set(null, repository);
        String vorher = lokalerStand();
        quelle.listen = Collections.singletonList(kopf(41, "Erste Gruppe"));
        try (ActivityScenario<MainActivity> activity = ActivityScenario.launch(new Intent(context, MainActivity.class))) {
            warteAufAnzeige(activity, R.id.listenLadestatusTextView, R.id.faelligeZaehlerlistenRecyclerView,
                    R.string.listenquelle_server, 1);
            assertEquals(1, quelle.aufrufe.get());
            activity.moveToState(Lifecycle.State.CREATED);
            quelle.listen = Arrays.asList(kopf(41, "Neue Gruppe"), kopf(42, "Zusaetzliche Gruppe"));
            activity.moveToState(Lifecycle.State.RESUMED);
            warteAufAnzeige(activity, R.id.listenLadestatusTextView, R.id.faelligeZaehlerlistenRecyclerView,
                    R.string.listenquelle_server, 2);
            assertEquals(2, quelle.aufrufe.get());
            assertEquals(vorher, lokalerStand());
        } finally {
            container.set(null, vorherigesRepository);
            if (vorherigerName.isEmpty()) sitzung.abmelden();
            else sitzung.setzeAngemeldetenZaehler(vorherigerName);
        }
    }

    private void warteAufAnzeige(ActivityScenario<? extends android.app.Activity> activity,
            int quellViewId, int listenViewId, int textId, int anzahl) throws Exception {
        long ende = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < ende) {
            boolean[] fertig = {false};
            activity.onActivity(a -> {
                TextView quelleText = a.findViewById(quellViewId);
                RecyclerView listen = a.findViewById(listenViewId);
                Long zeit = repository.holeLetztenErfolgreichenListenabrufMillis();
                String erwartet = textId == R.string.listenquelle_server && zeit != null
                        ? a.getString(textId, ListenLadestatusAnzeige.formatiereUhrzeit(zeit, java.util.TimeZone.getDefault()))
                        : a.getString(textId);
                fertig[0] = erwartet.contentEquals(quelleText.getText())
                        && a.findViewById(R.id.listenLadeanzeige).getVisibility() == android.view.View.GONE
                        && listen.getAdapter() != null && listen.getAdapter().getItemCount() == anzahl;
            });
            if (fertig[0]) return;
            Thread.sleep(20);
        }
        fail("Listenstand wurde nicht rechtzeitig angezeigt");
    }

    @Test public void fehlerVorErstemErfolgErzeugtKeinenZeitstempel() {
        java.util.concurrent.atomic.AtomicLong zeit = new java.util.concurrent.atomic.AtomicLong(1000L);
        repository = new ArtikelRepository(quelle, speicher, zeit::get);
        quelle.offline = true;
        String vorher = lokalerStand();
        Listenuebersicht stand = repository.aktualisiereListenuebersicht();
        assertFalse(stand.istServerstand());
        assertNull(stand.getLetzterErfolgreicherAbrufMillis());
        assertNull(repository.holeLetztenErfolgreichenListenabrufMillis());
        assertEquals(vorher, lokalerStand());
    }

    @Test public void zeitstempelAendertSichNurNachVollstaendigemErfolgreichemAbruf() {
        java.util.concurrent.atomic.AtomicLong zeit = new java.util.concurrent.atomic.AtomicLong(1000L);
        repository = new ArtikelRepository(quelle, speicher, zeit::get);
        String vorher = lokalerStand();
        quelle.listen = Collections.singletonList(kopf(41, "Server"));
        Listenuebersicht ersterStand = repository.aktualisiereListenuebersicht();
        assertEquals(Long.valueOf(1000L), ersterStand.getLetzterErfolgreicherAbrufMillis());
        zeit.set(2000L);
        quelle.offline = true;
        assertEquals(Long.valueOf(1000L), repository.aktualisiereListenuebersicht().getLetzterErfolgreicherAbrufMillis());
        quelle.offline = false;
        quelle.listen = Arrays.asList(kopf(41, "A"), kopf(41, "B"));
        zeit.set(3000L);
        assertFalse(repository.aktualisiereListenuebersicht().istServerstand());
        assertEquals(Long.valueOf(1000L), repository.holeLetztenErfolgreichenListenabrufMillis());
        quelle.listen = Collections.emptyList();
        zeit.set(4000L);
        Listenuebersicht zweiterStand = repository.aktualisiereListenuebersicht();
        assertTrue(zweiterStand.istServerstand());
        assertEquals(Long.valueOf(4000L), zweiterStand.getLetzterErfolgreicherAbrufMillis());
        assertEquals(Long.valueOf(1000L), ersterStand.getLetzterErfolgreicherAbrufMillis());
        assertEquals(vorher, lokalerStand());
    }

    @Test public void erfolgreicherVerbindungscheckGiltNichtAlsListenabruf() {
        KopfQuelle nurVerbindung = new KopfQuelle() {
            @Override public void pruefeVerbindung() { }
        };
        ArtikelRepository pruefling = new ArtikelRepository(nurVerbindung, speicher);
        String vorher = lokalerStand();
        pruefling.pruefeDatenbankverbindungUndSynchronisiereSofort();
        assertNull(pruefling.holeLetztenErfolgreichenListenabrufMillis());
        assertEquals(0, nurVerbindung.aufrufe.get());
        assertEquals(vorher, lokalerStand());
    }

    @Test public void ladeanzeigeUndUhrzeitFolgenDemTatsaechlichenAbruf() throws Exception {
        java.util.concurrent.atomic.AtomicLong zeit = new java.util.concurrent.atomic.AtomicLong(1000L);
        repository = new ArtikelRepository(quelle, speicher, zeit::get);
        Field container = AppContainer.class.getDeclaredField("apiArtikelRepository");
        container.setAccessible(true);
        Object vorherigesRepository = container.get(null);
        container.set(null, repository);
        quelle.listen = Collections.singletonList(kopf(41, "Server"));
        java.util.concurrent.CountDownLatch freigabe = new java.util.concurrent.CountDownLatch(1);
        quelle.warteAufFreigabe = freigabe;
        Intent intent = new Intent(context, ZaehlerlistenActivity.class)
                .putExtra(ZaehlerlistenActivity.EXTRA_AUSGEWAEHLTE_ZAEHLERLISTEN_ID, 41);
        try (ActivityScenario<ZaehlerlistenActivity> activity = ActivityScenario.launch(intent)) {
            activity.onActivity(a -> {
                TextView status = a.findViewById(R.id.listenLadestatusTextView);
                TextView letzterAbruf = a.findViewById(R.id.listenLetzterAbrufTextView);
                assertEquals(a.getString(R.string.listenquelle_laden), status.getText().toString());
                assertEquals(android.view.View.VISIBLE, a.findViewById(R.id.listenLadeanzeige).getVisibility());
                assertEquals(a.getString(R.string.listenquelle_noch_kein_abruf), letzterAbruf.getText().toString());
                assertNull(repository.holeLetztenErfolgreichenListenabrufMillis());
            });
            zeit.set(3_600_000L);
            freigabe.countDown();
            warteAufQuelle(activity, R.string.listenquelle_server, 1);
            assertEquals(Long.valueOf(3_600_000L), repository.holeLetztenErfolgreichenListenabrufMillis());
            activity.moveToState(Lifecycle.State.CREATED);
            quelle.offline = true;
            zeit.set(7_200_000L);
            activity.moveToState(Lifecycle.State.RESUMED);
            warteAufQuelle(activity, R.string.listenquelle_lokal, 1);
            activity.onActivity(a -> {
                TextView letzterAbruf = a.findViewById(R.id.listenLetzterAbrufTextView);
                assertEquals(a.getString(R.string.listenquelle_letzter_abruf,
                                ListenLadestatusAnzeige.formatiereUhrzeit(3_600_000L, java.util.TimeZone.getDefault())),
                        letzterAbruf.getText().toString());
                TextView sperre = a.findViewById(R.id.syncStatusTextView);
                assertEquals(a.getString(R.string.sync_status_erfolgreich),sperre.getText().toString());
            });
            assertEquals(Long.valueOf(3_600_000L), repository.holeLetztenErfolgreichenListenabrufMillis());
        } finally {
            freigabe.countDown();
            container.set(null, vorherigesRepository);
        }
    }

    private static class KopfQuelle implements InventurRemoteDataSource {
        final AtomicInteger aufrufe = new AtomicInteger();
        volatile List<ZaehlerlisteDto> listen = Collections.emptyList();
        volatile boolean offline;
        volatile java.util.concurrent.CountDownLatch warteAufFreigabe;
        @Override public boolean istSchreibgeschuetzt() { return !SqlServerSchreibschutz.istSchreibenErlaubt(); }
        @Override public List<ZaehlerlisteDto> ladeListenuebersicht() throws RemoteDatenException {
            aufrufe.incrementAndGet();
            if (warteAufFreigabe != null) {
                try {
                    if (!warteAufFreigabe.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new RemoteDatenException("Testabruf wurde nicht freigegeben");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RemoteDatenException("Testabruf abgebrochen", e);
                }
            }
            List<ZaehlerlisteDto> gelesen = listen == null ? null : new ArrayList<>(listen);
            if (offline) throw new RemoteDatenException("Simulierter SQL-Lesefehler");
            return gelesen;
        }
        @Override public List<ZaehlerlisteDto> ladeZaehlerlisten() { throw new AssertionError("Kein Bestandsimport erlaubt"); }
        @Override public List<ArtikelDto> ladeArtikel(int id) { throw new AssertionError("Keine Artikelabfrage erlaubt"); }
        @Override public void pruefeVerbindung() { throw new AssertionError("Kein allgemeiner Synchronisierungspfad erlaubt"); }
        @Override public ZaehlmengenSpeicherAntwort speichereZaehlmengen(ZaehlmengenSpeicherAnfrage anfrage) {
            throw new AssertionError("Kein SQL-Schreiben erlaubt");
        }
        @Override public void markiereZaehlerlisteAlsAbgeschlossen(int id) { throw new AssertionError("Kein SQL-Abschluss erlaubt"); }
        @Override public String holeLetzteDiagnose() { return ""; }
    }
}
