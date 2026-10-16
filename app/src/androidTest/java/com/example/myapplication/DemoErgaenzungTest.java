package com.example.myapplication;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DemoErgaenzungTest {
    private Context context;
    private String name;
    private LokalerInventurSpeicher speicher;
    private MockInventurRemoteDataSource demo;

    @Before
    public void vorbereiten() {
        // Eigene Test-App-Datenbank, niemals inventur_demo_offline.db verwenden.
        context = InstrumentationRegistry.getInstrumentation().getContext();
        name = "test-demo-" + UUID.randomUUID() + ".db";
        speicher = new LokalerInventurSpeicher(context, name);
        demo = new MockInventurRemoteDataSource();
    }

    @After
    public void aufraeumen() {
        speicher.close();
        context.deleteDatabase(name);
    }

    @Test
    public void neuinstallationHat25ArtikelJeListeAuchNachNeustart() {
        ArtikelRepository repository = new ArtikelRepository(demo, speicher);
        assertEquals(4, repository.ladeZaehlerlisten().size());
        pruefe25(repository);
        speicher.close();
        speicher = new LokalerInventurSpeicher(context, name);
        pruefe25(new ArtikelRepository(new MockInventurRemoteDataSource(), speicher));
        assertEquals(10, speicher.getReadableDatabase().getVersion());
    }

    @Test
    public void altinstallationBehaeltMengenNotizenStatusUndBelegtePositionen() {
        speicher.initialisiereZaehlerlisten(demo.ladeZaehlerlisten());
        speicher.initialisiereArtikel(1, Arrays.asList(
                new ArtikelDto(0, "ART-1010", "Altbestand", "Alt-Regal", 54, 20, 125),
                new ArtikelDto(5, "ART-1001", "Kollision", "Alt-Regal", 125, 80, 0),
                new ArtikelDto(2, "ART-1002", "Offener Altbestand", "Alt-Regal", 42, 12, null)));
        List<Artikel> vorher = speicher.ladeArtikel(1);
        vorher.get(0).setAnmerkung("Testnotiz erhalten");
        speicher.speichereZaehlmengen(1, "Testzaehler", "Testschreiber", vorher);
        speicher.markiereZaehlerlisteAlsAbgeschlossen(1);
        speicher.merkeZaehlerlisteFuerSynchronisierung(1);
        ArtikelRepository repository = new ArtikelRepository(demo, speicher);
        pruefe25(repository);
        pruefe25(repository);
        speicher.close();
        speicher = new LokalerInventurSpeicher(context, name);
        pruefe25(new ArtikelRepository(new MockInventurRemoteDataSource(), speicher));
        List<Artikel> nachher = speicher.ladeArtikel(1);
        assertEquals(0, nachher.get(0).getPositionsNummer());
        assertEquals("ART-1010", nachher.get(0).getArtikelnummer());
        assertEquals(Integer.valueOf(125), nachher.get(0).getZaehlMenge());
        assertEquals("Testnotiz erhalten", nachher.get(0).getAnmerkung());
        Artikel kollision = nachher.stream().filter(a -> a.getArtikelnummer().equals("ART-1001")).findFirst().get();
        assertEquals(5, kollision.getPositionsNummer());
        assertEquals(Integer.valueOf(0), kollision.getZaehlMenge());
        Artikel offen = nachher.stream().filter(a -> a.getArtikelnummer().equals("ART-1002")).findFirst().get();
        assertNull(offen.getZaehlMenge());
        assertEquals("Offener Altbestand", offen.getBezeichnung());
        Zaehlerliste liste = speicher.ladeZaehlerlisteFuerId(1);
        assertTrue(liste.istAbgeschlossen());
        assertEquals("Testzaehler", liste.getGespeicherterZaehlerName());
        assertEquals("Testschreiber", liste.getGespeicherterSchreiberName());
        assertTrue(speicher.ladeZaehlerlistenMitOffenerSynchronisierung().contains(1));
    }

    @Test
    public void unbekannteListenUndZusaetzlicheBenutzerArtikelBleibenErhalten() {
        speicher.initialisiereZaehlerlisten(Arrays.asList(
                demo.ladeZaehlerlisten().get(0),
                new ZaehlerlisteDto(9001, "Andere Liste", "9001", "Demo", "01.01.2026", true, false)));
        speicher.initialisiereArtikel(1, Arrays.asList(
                new ArtikelDto(1, "TEST-ZUSATZ", "Eigener Testartikel", "R1", 10, 1, 5)));
        speicher.initialisiereArtikel(9001, Arrays.asList(
                new ArtikelDto(1, "TEST-FREMD", "Andere Liste", "R2", 10, 1, null)));
        ArtikelRepository repository = new ArtikelRepository(demo, speicher);
        assertEquals(26, repository.ladeArtikel(1).size());
        assertEquals(26, repository.ladeArtikel(1).size());
        assertEquals("TEST-ZUSATZ", speicher.ladeArtikel(1).get(0).getArtikelnummer());
        assertEquals(Integer.valueOf(5), speicher.ladeArtikel(1).get(0).getZaehlMenge());
        assertEquals(1, repository.ladeArtikel(9001).size());
        assertEquals(2, repository.ladeZaehlerlisten().size());
    }

    private void pruefe25(ArtikelRepository repository) {
        for (int id = 1; id <= 4; id++) {
            List<Artikel> artikel = repository.ladeArtikel(id);
            assertEquals(25, artikel.size());
            HashSet<String> nummern = new HashSet<>();
            HashSet<Integer> positionen = new HashSet<>();
            for (Artikel zeile : artikel) {
                assertTrue(nummern.add(zeile.getArtikelnummer()));
                assertTrue(positionen.add(zeile.getPositionsNummer()));
            }
        }
    }
}
