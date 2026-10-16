package com.example.myapplication;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.*;

public class ArtikelFilterTest {
    @Test
    public void normaleSucheFindetAuchTeilwoerter() {
        Artikel artikel = artikel(17, "ART-ALPHA", "Schraubendreher", "Regal Nord", null, 5);
        assertTrue(suche(artikel, "rauben"));
        assertFalse(suche(artikel, "Mutter"));
    }

    @Test
    public void leereSuchbegriffeSchraenkenNichtEin() {
        Artikel artikel = artikel(17, "ART-ALPHA", "Schraube", "A1", null, 5);
        for (String text : new String[]{null, "", "  ", "\t\n"}) {
            assertEquals("", ArtikelFilter.normalisiereSuchbegriff(text));
            assertTrue(suche(artikel, text));
        }
    }

    @Test
    public void sucheIgnoriertGrossschreibungUndAeussereLeerzeichen() {
        mitSprache(Locale.GERMANY, () -> {
            Artikel artikel = artikel(17, "ART-ALPHA", "Ölschraube", "Regal Nord", null, 5);
            assertTrue(suche(artikel, "  ÖLSCHRAUBE  "));
            assertTrue(suche(artikel, "regal NORD"));
            assertFalse(suche(artikel, "Öl schraube"));
        });
    }

    @Test
    public void sucheBeruecksichtigtAlleVierArtikelfelder() {
        Artikel artikel = artikel(125, "ART-ALPHA", "Schraube", "Regal Nord", null, 5);
        for (String text : new String[]{"art-alpha", "schrau", "nord", "25"}) {
            assertTrue(text, suche(artikel, text));
        }
        assertFalse(suche(artikel, "unbekannt"));
    }

    @Test
    public void fehlendeTextfelderWerdenNichtAlsNullTextGesucht() {
        Artikel artikel = artikel(0, null, null, null, null, 5);
        assertTrue(suche(artikel, ""));
        assertTrue(suche(artikel, "0"));
        assertFalse(suche(artikel, "null"));
    }

    @Test
    public void nullIstOffenUndNullmengeIstGezaehlt() {
        Artikel artikel = artikel(17, "ART-ALPHA", "Schraube", "A1", null, 5);
        assertTrue(ArtikelFilter.passtZumFilter(artikel, ArtikelFilter.FILTER_OFFEN));
        assertFalse(ArtikelFilter.passtZumFilter(artikel, ArtikelFilter.FILTER_GEZAEHLT));
        artikel.setZaehlMenge(0);
        assertFalse(ArtikelFilter.passtZumFilter(artikel, ArtikelFilter.FILTER_OFFEN));
        assertTrue(ArtikelFilter.passtZumFilter(artikel, ArtikelFilter.FILTER_GEZAEHLT));
    }

    @Test
    public void mindestbestandVerlangtEineGezaehlteStriktKleinereMenge() {
        Artikel artikel = artikel(17, "ART-ALPHA", "Schraube", "A1", null, 5);
        assertFalse(ArtikelFilter.passtZumFilter(artikel, ArtikelFilter.FILTER_UNTER_MINDESTBESTAND));
        for (int menge : new int[]{0, 4}) {
            artikel.setZaehlMenge(menge);
            assertTrue(ArtikelFilter.passtZumFilter(artikel, ArtikelFilter.FILTER_UNTER_MINDESTBESTAND));
        }
        for (int menge : new int[]{5, 6}) {
            artikel.setZaehlMenge(menge);
            assertFalse(ArtikelFilter.passtZumFilter(artikel, ArtikelFilter.FILTER_UNTER_MINDESTBESTAND));
        }
        assertFalse(ArtikelFilter.passtZumFilter(
                artikel(1, "TEST", "Test", "A1", 0, 0), ArtikelFilter.FILTER_UNTER_MINDESTBESTAND));
    }

    @Test
    public void alleUndUnbekannteFiltermodiSchraenkenNichtEin() {
        for (Integer menge : new Integer[]{null, 0, 12}) {
            Artikel artikel = artikel(17, "ART-ALPHA", "Schraube", "A1", menge, 5);
            for (int modus : new int[]{ArtikelFilter.FILTER_ALLE, 0, -1, 99}) {
                assertTrue(ArtikelFilter.passtZumFilter(artikel, modus));
            }
        }
    }

    @Test
    public void geraetespracheBleibtAuchBeiTuerkischemIBedeutend() {
        mitSprache(Locale.forLanguageTag("tr-TR"), () -> {
            Artikel artikel = artikel(17, "I-ART", "Test", "A1", null, 5);
            assertEquals("ı", ArtikelFilter.normalisiereSuchbegriff("I"));
            assertTrue(suche(artikel, "I"));
            assertFalse(suche(artikel, "i"));
        });
    }

    private static boolean suche(Artikel artikel, String text) {
        return ArtikelFilter.passtZumSuchbegriff(artikel, ArtikelFilter.normalisiereSuchbegriff(text));
    }

    private static Artikel artikel(int position, String nummer, String text, String ort, Integer menge, int minimum) {
        return new Artikel(position, nummer, text, ort, 30, minimum, menge);
    }

    private static void mitSprache(Locale sprache, Runnable pruefung) {
        Locale vorher = Locale.getDefault();
        try {
            Locale.setDefault(sprache);
            pruefung.run();
        } finally {
            Locale.setDefault(vorher);
        }
    }
}
