package com.example.myapplication;

import org.junit.Test;
import static org.junit.Assert.*;

public class SynchronisierungsdiagnoseTest {
    @Test public void historischeRevisionenUndAltbestandSindKeineOffenenAuftraege() {
        Synchronisierungsdiagnose d=new Synchronisierungsdiagnose(0,0,0,25,5,1,6,0);
        assertFalse(d.hatOffeneAenderungen());
        assertTrue(d.getKurztext().contains("Keine offenen"));
        assertTrue(d.getDetails().contains("Revisionen ohne Änderungskennzeichen: 6"));
    }
    @Test public void warteschlangeBleibtEigenstaendigerSperrgrund() {
        assertTrue(new Synchronisierungsdiagnose(1,0,0,0,0,0,0,0).hatOffeneAenderungen());
    }
    @Test public void serverstandQuittiertKeineOffenenKennzeichen() {
        Synchronisierungsdiagnose d=new Synchronisierungsdiagnose(0,1,0,0,1,0,0,1);
        assertTrue(d.hatOffeneAenderungen());
        assertTrue(d.getKurztext().contains("warten auf Serverbestätigung"));
    }
    @Test public void kopfaenderungWirdNichtAlsZusaetzlichePositionGezaehlt() {
        Synchronisierungsdiagnose d=new Synchronisierungsdiagnose(1,5,1,25,5,1,0,0);
        assertEquals(5,d.getGeaendertePositionen());
        assertEquals(1,d.getGeaenderteKoepfe());
        assertTrue(d.getKurztext().contains("5 geänderte Positionen und 1 Kopf-/Abschlussänderung"));
    }
    @Test public void diagnoseFehlerIstKeinLeererErfolgsstand() {
        Synchronisierungsdiagnose d=Synchronisierungsdiagnose.nichtVerfuegbar();
        assertFalse(d.istVerfuegbar());
        assertTrue(d.hatOffeneAenderungen());
        assertFalse(d.getDetails().contains("Keine offenen"));
    }
    @Test public void gespeicherterServerstandWirdNichtAlsEchteQuittierungBehauptet() {
        String text=new Synchronisierungsdiagnose(0,0,0,0,2,0,2,2).getDetails();
        assertTrue(text.contains("Datensätze mit gespeichertem Serverstand: 2"));
        assertTrue(text.contains("allein sind keine Übertragungsbestätigung"));
    }
}
