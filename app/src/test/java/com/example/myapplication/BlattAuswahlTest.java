package com.example.myapplication;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.*;

public class BlattAuswahlTest {
    @Test
    public void idSucheLiefertDenIndexInDerBestehendenReihenfolge() {
        List<Zaehlerliste> blaetter = Arrays.asList(blatt(30), blatt(10), blatt(20));
        assertEquals(1, BlattAuswahl.findeBlattIndex(blaetter, 10));
        assertEquals(0, BlattAuswahl.findeBlattIndex(blaetter, 30));
        assertEquals(2, BlattAuswahl.findeBlattIndex(blaetter, 20));
    }

    @Test
    public void doppelteIdLiefertDenErstenTreffer() {
        assertEquals(0, BlattAuswahl.findeBlattIndex(Arrays.asList(blatt(10), blatt(10)), 10));
    }

    @Test
    public void unbekannteUndFehlendeIdLiefertMinusEins() {
        List<Zaehlerliste> blaetter = Collections.singletonList(blatt(10));
        assertEquals(-1, BlattAuswahl.findeBlattIndex(blaetter, 99));
        assertEquals(-1, BlattAuswahl.findeBlattIndex(blaetter, -1));
        assertEquals(-1, BlattAuswahl.findeBlattIndex(blaetter, 0));
        assertEquals(-1, BlattAuswahl.findeBlattIndex(Collections.emptyList(), 10));
    }

    @Test
    public void idSucheFuehrtKeineZusaetzlicheIdValidierungEin() {
        List<Zaehlerliste> blaetter = Arrays.asList(blatt(0), blatt(-1));
        assertEquals(0, BlattAuswahl.findeBlattIndex(blaetter, 0));
        assertEquals(1, BlattAuswahl.findeBlattIndex(blaetter, -1));
    }

    @Test
    public void idSucheLehntNichtUnterstuetzteNullListenUndEintraegeWeiterhinAb() {
        assertThrows(NullPointerException.class, () -> BlattAuswahl.findeBlattIndex(null, 10));
        assertThrows(NullPointerException.class,
                () -> BlattAuswahl.findeBlattIndex(Collections.singletonList(null), 10));
    }

    @Test
    public void bezeichnungWirdNormalisiertAberNichtAlsTeilwortVerglichen() {
        Zaehlerliste passend = blatt(1, " Wochenliste Nord ", "Z", "S", "wo");
        Zaehlerliste teilwort = blatt(2, "Wochenliste Nord Zusatz", "Z", "S", "wo");
        assertEquals(Collections.singletonList(passend), BlattAuswahl.wochenBlaetter(
                Arrays.asList(passend, teilwort), "Z", "S", " WOCHENLISTE NORD "));
    }

    @Test
    public void nichtGesetzteBezeichnungSchraenktDieAuswahlNichtEin() {
        List<Zaehlerliste> blaetter = Arrays.asList(
                blatt(1, "Liste A", "Z", "S", "wo"), blatt(2, null, "Z", "S", "wo"));
        for (String filter : new String[]{null, "", " \t "}) {
            assertEquals(blaetter, BlattAuswahl.wochenBlaetter(blaetter, "Z", "S", filter));
        }
    }

    @Test
    public void zaehlerMussNachNormalisierungVollstaendigPassen() {
        Zaehlerliste passend = blatt(1, "Liste", " Zaehler A ", "S", "wo");
        List<Zaehlerliste> blaetter = Arrays.asList(passend,
                blatt(2, "Liste", "Zaehler AB", "S", "wo"));
        assertEquals(Collections.singletonList(passend),
                BlattAuswahl.wochenBlaetter(blaetter, "ZAEHLER A", "S", ""));
        assertTrue(BlattAuswahl.wochenBlaetter(blaetter, "Zaehler", "S", "").isEmpty());
    }

    @Test
    public void schreiberMussNachNormalisierungVollstaendigPassen() {
        Zaehlerliste passend = blatt(1, "Liste", "Z", " Schreiber A ", "wo");
        List<Zaehlerliste> blaetter = Arrays.asList(passend,
                blatt(2, "Liste", "Z", "Schreiber AB", "wo"));
        assertEquals(Collections.singletonList(passend),
                BlattAuswahl.wochenBlaetter(blaetter, "Z", "SCHREIBER A", ""));
        assertTrue(BlattAuswahl.wochenBlaetter(blaetter, "Z", "Schreiber", "").isEmpty());
    }

    @Test
    public void leereNamensfilterSindKeinePlatzhalterFuerBeliebigeNamen() {
        Zaehlerliste beideLeer = blatt(1, "Liste", null, " \t ", "wo");
        Zaehlerliste zaehlerLeer = blatt(2, "Liste", "", "S", "wo");
        Zaehlerliste schreiberLeer = blatt(3, "Liste", "Z", "", "wo");
        List<Zaehlerliste> blaetter = Arrays.asList(beideLeer, zaehlerLeer, schreiberLeer);
        assertEquals(Collections.singletonList(beideLeer),
                BlattAuswahl.wochenBlaetter(blaetter, null, null, null));
        assertEquals(Collections.singletonList(zaehlerLeer),
                BlattAuswahl.wochenBlaetter(blaetter, " ", "S", ""));
        assertEquals(Collections.singletonList(schreiberLeer),
                BlattAuswahl.wochenBlaetter(blaetter, "Z", "", ""));
    }

    @Test
    public void nurWoWirdAlsZaehltypAkzeptiertOhneLeerzeichenZuEntfernen() {
        List<Zaehlerliste> blaetter = new ArrayList<>();
        for (String typ : new String[]{"wo", "WO", "wO", " wo ", "mo", "", null}) {
            blaetter.add(blatt(blaetter.size() + 1, "Liste", "Z", "S", typ));
        }
        assertEquals(blaetter.subList(0, 3), BlattAuswahl.wochenBlaetter(blaetter, "Z", "S", ""));
    }

    @Test
    public void alleVorhandenenFilterWerdenMitUndVerknuepft() {
        Zaehlerliste passend = blatt(1, "Nord", "Z", "S", "wo");
        List<Zaehlerliste> blaetter = Arrays.asList(passend,
                blatt(2, "Sued", "Z", "S", "wo"), blatt(3, "Nord", "Anderer", "S", "wo"),
                blatt(4, "Nord", "Z", "Anderer", "wo"), blatt(5, "Nord", "Z", "S", "mo"));
        assertEquals(Collections.singletonList(passend),
                BlattAuswahl.wochenBlaetter(blaetter, "z", "s", "nord"));
    }

    @Test
    public void reihenfolgeOriginalobjekteUndEingabelisteBleibenErhalten() {
        List<Zaehlerliste> blaetter = new ArrayList<>(Arrays.asList(blatt(30), blatt(10), blatt(20)));
        List<Zaehlerliste> vorher = new ArrayList<>(blaetter);
        List<Zaehlerliste> auswahl = BlattAuswahl.wochenBlaetter(blaetter, "Z", "S", "Liste");
        assertEquals(vorher, auswahl);
        assertNotSame(blaetter, auswahl);
        assertSame(blaetter.get(0), auswahl.get(0));
        assertEquals(1, BlattAuswahl.findeBlattIndex(auswahl, 10));
        auswahl.remove(0);
        assertEquals(vorher, blaetter);
    }

    @Test
    public void leereEingabelisteErgibtEineEigeneVeraenderbareLeereListe() {
        List<Zaehlerliste> eingabe = Collections.emptyList();
        List<Zaehlerliste> auswahl = BlattAuswahl.wochenBlaetter(eingabe, "Z", "S", "");
        assertTrue(auswahl.isEmpty());
        assertNotSame(eingabe, auswahl);
        auswahl.add(blatt(1));
        assertTrue(eingabe.isEmpty());
    }

    @Test
    public void fehlendeTrefferFuehrenZuKeinemRueckgriffAufAndereBlaetter() {
        assertTrue(BlattAuswahl.wochenBlaetter(
                Collections.singletonList(blatt(1)), "Z", "S", "Unbekannt").isEmpty());
    }

    @Test
    public void wochenAuswahlLehntNichtUnterstuetzteNullListenUndEintraegeWeiterhinAb() {
        assertThrows(NullPointerException.class, () -> BlattAuswahl.wochenBlaetter(null, "", "", ""));
        assertThrows(NullPointerException.class,
                () -> BlattAuswahl.wochenBlaetter(Collections.singletonList(null), "", "", ""));
    }

    @Test
    public void normalisierungVerwendetWeiterhinDeutschAuchBeiAndererGeraetesprache() {
        Locale vorher = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            Zaehlerliste blatt = blatt(1, "I", "I", "I", "wo");
            assertEquals("i", BlattAuswahl.normalisiereText(" I "));
            assertEquals("", BlattAuswahl.normalisiereText(null));
            assertEquals(Collections.singletonList(blatt), BlattAuswahl.wochenBlaetter(
                    Collections.singletonList(blatt), "i", "i", "i"));
        } finally {
            Locale.setDefault(vorher);
        }
    }

    @Test
    public void abschlussUndFaelligkeitSindKeineZusaetzlichenAuswahlbedingungen() {
        Zaehlerliste offen = blatt(1);
        Zaehlerliste abgeschlossen = new Zaehlerliste(2, "Liste", "B2", "Demo", "", false, true,
                "Z", "S", "wo");
        List<Zaehlerliste> blaetter = Arrays.asList(offen, abgeschlossen);
        assertEquals(blaetter, BlattAuswahl.wochenBlaetter(blaetter, "Z", "S", "Liste"));
    }

    private static Zaehlerliste blatt(int id) {
        return blatt(id, "Liste", "Z", "S", "wo");
    }

    private static Zaehlerliste blatt(int id, String bezeichnung, String zaehler, String schreiber, String typ) {
        return new Zaehlerliste(id, bezeichnung, "B" + id, "Demo", "", true, false, zaehler, schreiber, typ);
    }
}
