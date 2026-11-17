package com.example.myapplication;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SqlPositionspruefungTest {
    private Context context;
    private String name;
    private LokalerInventurSpeicher speicher;
    private Quelle quelle;
    private ArtikelRepository repository;

    @Before public void vorbereiten() {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        name="positionspruefung_"+UUID.randomUUID()+".db";
        speicher=new LokalerInventurSpeicher(context,name);
        speicher.initialisiereZaehlerlisten(Arrays.asList(
                new ZaehlerlisteDto(41,"Testgruppe","41","Test","",true,false),
                new ZaehlerlisteDto(42,"Testgruppe","42","Test","",true,false)));
        speicher.initialisiereArtikel(41,Arrays.asList(dto(701,1,"TEST-A",null),dto(702,2,"TEST-B",0)));
        quelle=new Quelle();
        repository=new ArtikelRepository(quelle,speicher);
    }
    @After public void aufraeumen() { speicher.close(); context.deleteDatabase(name); }
    private ArtikelDto dto(long id,int pos,String artikel,Integer menge) {
        return new ArtikelDto(pos,artikel,"Test","L1",3,null,1,menge,"",id,41);
    }
    private String stand() {
        StringBuilder text=new StringBuilder();
        for(String t:Arrays.asList("artikel","zaehlerliste","zaehlerlisten_status","sync_warteschlange")) {
            try(Cursor c=speicher.getReadableDatabase().rawQuery("SELECT * FROM "+t+" ORDER BY rowid",null)) {
                while(c.moveToNext()) for(int i=0;i<c.getColumnCount();i++) {
                    String wert=c.isNull(i)?null:c.getString(i);
                    text.append(wert==null?-1:wert.length()).append(':').append(wert).append(';');
                }
            }
        }
        return text.toString();
    }
    private void bearbeiten() {
        List<Artikel> artikel=speicher.ladeArtikel(41);
        artikel.get(0).setAnmerkung("Lokale Notiz");
        artikel.get(1).setAnmerkung("Nullmenge erhalten");
        speicher.speichereZaehlmengen(41,"Testzaehler","Testschreiber",artikel);
    }

    @Test public void erneutesOeffnenFragtDasselbeBlattErneutOhneImportAb() {
        assertEquals(2,repository.pruefeSqlPositionen(41).getBekannt());
        quelle.antwort=Arrays.asList(new SqlPosition(701L,41,1,"TEST-A"),new SqlPosition(702L,41,2,"TEST-B"),
                new SqlPosition(703L,41,3,"TEST-C"));
        String vorher=stand();
        assertEquals(1,repository.pruefeSqlPositionen(41).getUnbekannt());
        assertEquals(2,quelle.abrufe); assertEquals(41,quelle.blatt);
        assertEquals(vorher,stand()); assertEquals(2,speicher.ladeArtikel(41).size());
    }
    @Test public void offlineUndWiederkehrBleibenOhneSchreibzugriff() {
        bearbeiten(); String vorher=stand(); quelle.fehler=true;
        assertFalse(repository.pruefeSqlPositionen(41).istAbrufErfolgreich());
        assertEquals(vorher,stand());
        quelle.fehler=false;
        assertEquals(2,repository.pruefeSqlPositionen(41).getBekannt());
        assertEquals(vorher,stand()); assertTrue(repository.istSqlSchreibenGesperrt());
    }
    @Test public void fehlerhafteAntwortBleibtUngeklaertUndUnveraendert() {
        String vorher=stand(); quelle.antwort=null;
        assertFalse(repository.pruefeSqlPositionen(41).istAbrufErfolgreich());
        quelle.antwort=Arrays.asList(new SqlPosition(701L,41,1,"TEST-A"),new SqlPosition(701L,41,2,"TEST-B"));
        assertTrue(repository.pruefeSqlPositionen(41).getUnklar()>0);
        assertEquals(vorher,stand());
    }
    @Test public void abgeschlossenerBestandMitOffenenMengenUndNotizenBleibtVollstaendigErhalten() {
        bearbeiten(); speicher.markiereZaehlerlisteAlsAbgeschlossen(41);
        String vorher=stand(); quelle.antwort=Collections.singletonList(new SqlPosition(701L,41,9,"TEST-C"));
        SqlPositionsvergleich v=repository.pruefeSqlPositionen(41);
        assertEquals(1,v.getGeaendert()); assertEquals(1,v.getFehlend());
        assertEquals(vorher,stand());
        assertNull(speicher.ladeArtikel(41).get(0).getZaehlMenge());
        assertEquals(Integer.valueOf(0),speicher.ladeArtikel(41).get(1).getZaehlMenge());
        assertTrue(speicher.hatOffeneSynchronisierung());
    }
    @Test public void altbestandOhneSqlIdBleibtGeschuetzt() {
        speicher.initialisiereArtikel(42,Collections.singletonList(new ArtikelDto(1,"TEST-ALT","Alt","L1",3,1,null)));
        quelle.antwort=Collections.singletonList(new SqlPosition(703L,42,1,"TEST-ALT"));
        String vorher=stand();
        assertTrue(repository.pruefeSqlPositionen(42).getUnklar()>0);
        assertEquals(vorher,stand());
    }
    @Test public void lokaleAenderungenWaehrendDesAbrufsWerdenNichtZurueckgesetzt() throws Exception {
        quelle.begonnen=new CountDownLatch(1); quelle.freigabe=new CountDownLatch(1);
        AtomicReference<SqlPositionsvergleich> ergebnis=new AtomicReference<>();
        Thread abruf=new Thread(()->ergebnis.set(repository.pruefeSqlPositionen(41)));
        abruf.start();
        try {
            assertTrue(quelle.begonnen.await(5,TimeUnit.SECONDS));
            bearbeiten(); speicher.markiereZaehlerlisteAlsAbgeschlossen(41);
            // Die Pruefung muss auch den erst waehrend des Abrufs gespeicherten Bestand lesen.
            speicher.getWritableDatabase().execSQL("UPDATE artikel SET pos_nr=9 WHERE sql_positions_id='701'");
            String aktuell=stand(); quelle.freigabe.countDown(); abruf.join(5000);
            assertFalse(abruf.isAlive()); assertEquals(1,ergebnis.get().getGeaendert());
            assertEquals(aktuell,stand());
        } finally { quelle.freigabe.countDown(); abruf.join(5000); }
    }
    @Test public void klassischeAnsichtOhneTestelementeUndLeerflaeche() throws Exception {
        anzeige(ArtikeluebersichtActivity.class);
    }
    @Test public void zweiteAnsichtOhneTestelementeUndLeerflaeche() throws Exception {
        anzeige(ArtikeluebersichtV2Activity.class);
    }

    private void anzeige(Class<? extends Activity> typ) throws Exception {
        Field container=AppContainer.class.getDeclaredField("apiArtikelRepository"); container.setAccessible(true);
        Object vorher=container.get(null);
        Benutzersitzung sitzung=new Benutzersitzung(context);
        String vorherigerName=sitzung.holeAngemeldetenZaehler();
        ArtikelRepository ansichtsRepository=new ArtikelRepository(quelle,speicher) {
            @Override public List<Zaehlerliste> ladeZaehlerlisten() { return speicher.ladeZaehlerlisten(); }
            @Override public List<Artikel> ladeArtikel(int id) { return speicher.ladeArtikel(id); }
            @Override public Zaehlerliste ladeZaehlerlisteFuerId(int id) { return speicher.ladeZaehlerlisteFuerId(id); }
        };
        quelle.antwort=Arrays.asList(new SqlPosition(701L,41,1,"TEST-A"),new SqlPosition(702L,41,2,"TEST-B"),
                new SqlPosition(703L,41,3,"TEST-C"));
        try {
            container.set(null,ansichtsRepository); sitzung.setzeAngemeldetenZaehler("Testzaehler");
            for(int oeffnung=0;oeffnung<2;oeffnung++) {
                try(ActivityScenario<Activity> scenario=ActivityScenario.launch(new Intent(context,typ)
                        .putExtra(ArtikeluebersichtActivity.EXTRA_ZAEHLERLISTEN_ID,41))) {
                    final int ausrichtung=oeffnung==0 ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                    scenario.onActivity(a -> a.setRequestedOrientation(ausrichtung));
                    long ende=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                    boolean[] geladen={false};
                    while(!geladen[0] && System.nanoTime()<ende) {
                        scenario.onActivity(a -> {
                            androidx.recyclerview.widget.RecyclerView liste=a.findViewById(R.id.artikelRecyclerView);
                            geladen[0]=liste.getAdapter()!=null && liste.getAdapter().getItemCount()==2
                                    && liste.isLaidOut() && liste.getHeight()>0;
                        });
                        if(!geladen[0])Thread.sleep(20);
                    }
                    assertTrue("Artikel muessen weiterhin geladen werden",geladen[0]);
                    scenario.onActivity(a -> {
                        for(String id:Arrays.asList("sqlPositionsstatus","sqlMengenSynchronisieren","sqlIdentitaetPruefen","sqlEinzelversuch"))
                            assertEquals("Entfernte Layout-ID: "+id,0,a.getResources().getIdentifier(id,"id",a.getPackageName()));
                        android.view.View liste=a.findViewById(R.id.artikelRecyclerView);
                        android.view.ViewGroup parent=(android.view.ViewGroup)liste.getParent();
                        android.view.View vorgaenger=null;
                        for(int i=0;i<parent.indexOfChild(liste);i++)
                            if(parent.getChildAt(i).getVisibility()!=android.view.View.GONE)vorgaenger=parent.getChildAt(i);
                        assertNotNull(vorgaenger);
                        int abstand=((android.view.ViewGroup.MarginLayoutParams)liste.getLayoutParams()).topMargin;
                        assertEquals("Kein Platzhalter vor der Artikelliste",vorgaenger.getBottom()+abstand,liste.getTop());
                    });
                    assertEquals(2,speicher.ladeArtikel(41).size());
                }
            }
            assertEquals(0,quelle.abrufe);
        } finally {
            container.set(null,vorher);
            if(vorherigerName.isEmpty()) sitzung.abmelden(); else sitzung.setzeAngemeldetenZaehler(vorherigerName);
        }
    }

    private static class Quelle implements InventurRemoteDataSource {
        List<SqlPosition> antwort=Arrays.asList(new SqlPosition(701L,41,1,"TEST-A"),new SqlPosition(702L,41,2,"TEST-B"));
        boolean fehler;
        int abrufe,blatt;
        CountDownLatch begonnen,freigabe;
        @Override public List<SqlPosition> ladeSqlPositionen(int id) throws RemoteDatenException {
            abrufe++; blatt=id;
            if(begonnen!=null) {
                begonnen.countDown();
                try { if(!freigabe.await(5,TimeUnit.SECONDS)) throw new RemoteDatenException("Testfreigabe fehlt"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new RemoteDatenException("Unterbrochen",e); }
            }
            if(fehler) throw new RemoteDatenException("Offline-Test");
            return antwort;
        }
        @Override public boolean istSchreibgeschuetzt() { return true; }
        @Override public void pruefeVerbindung() { throw new AssertionError("Keine weitere SQL-Abfrage"); }
        @Override public List<ZaehlerlisteDto> ladeZaehlerlisten() { throw new AssertionError("Keine Listenabfrage"); }
        @Override public List<ArtikelDto> ladeArtikel(int id) { throw new AssertionError("Kein Mengenabruf"); }
        @Override public ZaehlmengenSpeicherAntwort speichereZaehlmengen(ZaehlmengenSpeicherAnfrage a) { throw new AssertionError("SQL-Schreibzugriff"); }
        @Override public void markiereZaehlerlisteAlsAbgeschlossen(int id) { throw new AssertionError("SQL-Schreibzugriff"); }
        @Override public String holeLetzteDiagnose() { return ""; }
    }
}
