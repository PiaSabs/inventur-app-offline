package com.example.myapplication;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class ArtikelSnapshotsTest {
    @Test
    public void uebernimmtAlleNeunArtikelfelder() {
        Artikel original = new Artikel(17, "TEST-17", "Testartikel", "Regal A", 125, 12, 5, 42, "Notiz");
        Artikel kopie = ArtikelSnapshots.erstelle(Collections.singletonList(original)).get(0);

        assertNotSame(original, kopie);
        assertEquals(17, kopie.getPositionsNummer());
        assertEquals("TEST-17", kopie.getArtikelnummer());
        assertEquals("Testartikel", kopie.getBezeichnung());
        assertEquals("Regal A", kopie.getLagerort());
        assertEquals(125, kopie.getIstMenge());
        assertEquals(Integer.valueOf(12), kopie.getLetzteZaehlmenge());
        assertEquals(5, kopie.getMindestbestand());
        assertEquals(Integer.valueOf(42), kopie.getZaehlMenge());
        assertEquals("Notiz", kopie.getAnmerkung());
    }

    @Test
    public void nullMengenBleibenNull() {
        Artikel original = new Artikel(1, "TEST", "Test", "A1", 10, null, 5, null);
        Artikel kopie = ArtikelSnapshots.erstelle(Collections.singletonList(original)).get(0);

        assertNull(kopie.getZaehlMenge());
        assertNull(kopie.getLetzteZaehlmenge());
        assertFalse(kopie.istGezaehlt());
    }

    @Test
    public void mengenVonNullBleibenGezaehlt() {
        Artikel original = new Artikel(1, "TEST", "Test", "A1", 0, 0, 0, 0);
        Artikel kopie = ArtikelSnapshots.erstelle(Collections.singletonList(original)).get(0);

        assertEquals(0, kopie.getIstMenge());
        assertEquals(Integer.valueOf(0), kopie.getZaehlMenge());
        assertEquals(Integer.valueOf(0), kopie.getLetzteZaehlmenge());
        assertEquals(0, kopie.getMindestbestand());
        assertTrue(kopie.istGezaehlt());
    }

    @Test
    public void notizenBleibenMitLeerzeichenUndZeilenumbruechenErhalten() {
        String notiz = "  Prüfung: Größe M\nZweite Zeile\t ";
        Artikel original = artikel(1);
        original.setAnmerkung(notiz);

        assertEquals(notiz, ArtikelSnapshots.erstelle(Collections.singletonList(original)).get(0).getAnmerkung());
    }

    @Test
    public void aenderungenAmOriginalVeraendernDenSnapshotNicht() {
        Artikel original = artikel(1);
        original.setZaehlMenge(5);
        original.setAnmerkung("Vorher");
        Artikel kopie = ArtikelSnapshots.erstelle(Collections.singletonList(original)).get(0);

        original.setZaehlMenge(null);
        original.setAnmerkung("Nachher");

        assertEquals(Integer.valueOf(5), kopie.getZaehlMenge());
        assertEquals("Vorher", kopie.getAnmerkung());
    }

    @Test
    public void aenderungenAmSnapshotVeraendernDasOriginalNicht() {
        Artikel original = artikel(1);
        original.setZaehlMenge(null);
        original.setAnmerkung("Original");
        Artikel kopie = ArtikelSnapshots.erstelle(Collections.singletonList(original)).get(0);

        kopie.setZaehlMenge(0);
        kopie.setAnmerkung("Snapshot");

        assertNull(original.getZaehlMenge());
        assertEquals("Original", original.getAnmerkung());
    }

    @Test
    public void mehrereArtikelBehaltenIhreReihenfolge() {
        List<Artikel> originale = Arrays.asList(artikel(30), artikel(10), artikel(20));
        List<Artikel> snapshot = ArtikelSnapshots.erstelle(originale);

        assertEquals(3, snapshot.size());
        assertEquals(30, snapshot.get(0).getPositionsNummer());
        assertEquals(10, snapshot.get(1).getPositionsNummer());
        assertEquals(20, snapshot.get(2).getPositionsNummer());
        for (int index = 0; index < originale.size(); index++) {
            assertNotSame(originale.get(index), snapshot.get(index));
        }
    }

    @Test
    public void beideListenSindUnabhaengigVeraenderbar() {
        List<Artikel> originale = new ArrayList<>(Arrays.asList(artikel(1), artikel(2)));
        List<Artikel> snapshot = ArtikelSnapshots.erstelle(originale);

        originale.remove(0);
        assertEquals(2, snapshot.size());
        snapshot.clear();
        assertEquals(1, originale.size());
        assertEquals(2, originale.get(0).getPositionsNummer());
    }

    @Test
    public void leereListeErgibtEineEigeneVeraenderbareLeereListe() {
        List<Artikel> original = Collections.emptyList();
        List<Artikel> snapshot = ArtikelSnapshots.erstelle(original);

        assertTrue(snapshot.isEmpty());
        assertNotSame(original, snapshot);
        snapshot.add(artikel(1));
        assertTrue(original.isEmpty());
    }

    @Test
    public void vorhandeneNullTextfelderUndModellnormalisierungBleibenErhalten() {
        Artikel original = new Artikel(0, null, null, null, 10, null, 5, null, null);
        Artikel kopie = ArtikelSnapshots.erstelle(Collections.singletonList(original)).get(0);

        assertNull(kopie.getArtikelnummer());
        assertNull(kopie.getBezeichnung());
        assertEquals("", kopie.getLagerort());
        assertEquals("", kopie.getAnmerkung());
    }

    @Test
    public void nullListeWirdWeiterhinAbgelehnt() {
        assertThrows(NullPointerException.class, () -> ArtikelSnapshots.erstelle(null));
    }

    @Test
    public void nullEintragWirdWeiterhinAbgelehnt() {
        assertThrows(NullPointerException.class,
                () -> ArtikelSnapshots.erstelle(Arrays.asList(artikel(1), null)));
    }

    private static Artikel artikel(int position) {
        return new Artikel(position, "TEST-" + position, "Testartikel", "A1", 10, 5, null);
    }
}
