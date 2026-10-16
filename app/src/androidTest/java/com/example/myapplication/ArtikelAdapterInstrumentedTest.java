package com.example.myapplication;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.FutureTask;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ArtikelAdapterInstrumentedTest {
    @Test
    public void beideAdapterVerknuepfenSucheUndStatusMitUnd() throws Exception {
        aufHauptthread(() -> {
            List<Artikel> daten = daten();
            for (RecyclerView.Adapter<?> adapter : adapter(daten)) {
                setzeSuche(adapter, "SCHRAUBE");
                assertEquals(Arrays.asList(daten.get(1), daten.get(2), daten.get(3)), sichtbar(adapter));
                setzeModus(adapter, ArtikelFilter.FILTER_OFFEN);
                assertEquals(Arrays.asList(daten.get(1)), sichtbar(adapter));
                setzeModus(adapter, ArtikelFilter.FILTER_GEZAEHLT);
                assertEquals(Arrays.asList(daten.get(2), daten.get(3)), sichtbar(adapter));
                setzeModus(adapter, ArtikelFilter.FILTER_UNTER_MINDESTBESTAND);
                assertEquals(Arrays.asList(daten.get(3)), sichtbar(adapter));
                setzeSuche(adapter, "unbekannt");
                assertEquals(0, adapter.getItemCount());
            }
        });
    }

    @Test
    public void beideAdapterErhaltenEingabereihenfolgeUndOriginalobjekte() throws Exception {
        aufHauptthread(() -> {
            List<Artikel> daten = daten();
            List<Artikel> vorher = new ArrayList<>(daten);
            for (RecyclerView.Adapter<?> adapter : adapter(daten)) {
                setzeModus(adapter, ArtikelFilter.FILTER_GEZAEHLT);
                assertEquals(Arrays.asList(daten.get(0), daten.get(2), daten.get(3), daten.get(4)), sichtbar(adapter));
                setzeSuche(adapter, "");
                setzeModus(adapter, ArtikelFilter.FILTER_ALLE);
                assertEquals(vorher, sichtbar(adapter));
                assertSame(daten.get(0), sichtbar(adapter).get(0));
                assertEquals(vorher, daten);
            }
        });
    }

    @Test
    public void beideAdapterLesenAenderungenBeiErneuterFilterungUndBenachrichtigen() throws Exception {
        aufHauptthread(() -> {
            for (RecyclerView.Adapter<?> adapter : adapter(daten())) {
                int[] meldungen = {0};
                adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
                    @Override
                    public void onChanged() {
                        meldungen[0]++;
                    }
                });
                setzeModus(adapter, ArtikelFilter.FILTER_OFFEN);
                Artikel offen = (Artikel) sichtbar(adapter).get(0);
                offen.setZaehlMenge(0);
                assertEquals(1, adapter.getItemCount());
                setzeModus(adapter, ArtikelFilter.FILTER_OFFEN);
                assertEquals(0, adapter.getItemCount());
                setzeModus(adapter, ArtikelFilter.FILTER_GEZAEHLT);
                assertEquals(5, adapter.getItemCount());
                setzeSuche(adapter, null);
                assertEquals(5, adapter.getItemCount());
                assertEquals(4, meldungen[0]);
            }
        });
    }

    private interface AdapterPruefung {
        void pruefe() throws Exception;
    }

    private static void aufHauptthread(AdapterPruefung pruefung) throws Exception {
        FutureTask<Void> aufgabe = new FutureTask<>(() -> {
            pruefung.pruefe();
            return null;
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(aufgabe);
        aufgabe.get();
    }

    private static Artikel artikel(int position, String nummer, String text, String ort, Integer menge, int minimum) {
        return new Artikel(position, nummer, text, ort, 30, minimum, menge);
    }

    private static List<Artikel> daten() {
        return Arrays.asList(
                artikel(30, "ART-GAMMA", "Mutter", "A1", 4, 5),
                artikel(10, "ART-ALPHA", "Schraube", "A1", null, 5),
                artikel(50, "ART-EPSILON", "Schraube", "A1", 12, 5),
                artikel(20, "ART-BETA", "Schraube", "B1", 0, 5),
                artikel(40, "ART-DELTA", "Mutter", "C1", 5, 5));
    }

    private static List<RecyclerView.Adapter<?>> adapter(List<Artikel> daten) {
        return Arrays.asList(new ArtikelAdapter(daten, true, null), new ArtikelAdapterV2(daten, null));
    }

    private static List<?> sichtbar(RecyclerView.Adapter<?> adapter) throws Exception {
        // Prüft die Reihenfolge, ohne die öffentliche Adapter-Schnittstelle zu erweitern.
        Field feld = adapter.getClass().getDeclaredField("sichtbareArtikel");
        feld.setAccessible(true);
        return (List<?>) feld.get(adapter);
    }

    private static void setzeSuche(RecyclerView.Adapter<?> adapter, String text) {
        if (adapter instanceof ArtikelAdapter) {
            ((ArtikelAdapter) adapter).setSuchbegriff(text);
        } else {
            ((ArtikelAdapterV2) adapter).setSuchbegriff(text);
        }
    }

    private static void setzeModus(RecyclerView.Adapter<?> adapter, int modus) {
        if (adapter instanceof ArtikelAdapter) {
            ArtikelAdapter original = (ArtikelAdapter) adapter;
            switch (modus) {
                case ArtikelFilter.FILTER_OFFEN: original.zeigeNurUngezaehlteArtikel(); break;
                case ArtikelFilter.FILTER_GEZAEHLT: original.zeigeNurGezaehlteArtikel(); break;
                case ArtikelFilter.FILTER_UNTER_MINDESTBESTAND: original.zeigeNurUnterMindestbestandArtikel(); break;
                default: original.zeigeAlleArtikel();
            }
        } else {
            ArtikelAdapterV2 v2 = (ArtikelAdapterV2) adapter;
            switch (modus) {
                case ArtikelFilter.FILTER_OFFEN: v2.zeigeNurUngezaehlteArtikel(); break;
                case ArtikelFilter.FILTER_GEZAEHLT: v2.zeigeNurGezaehlteArtikel(); break;
                case ArtikelFilter.FILTER_UNTER_MINDESTBESTAND: v2.zeigeNurUnterMindestbestandArtikel(); break;
                default: v2.zeigeAlleArtikel();
            }
        }
    }

}
