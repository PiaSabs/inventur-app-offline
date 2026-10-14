package com.example.myapplication;

import org.junit.Test;

public class ExampleUnitTest {
    @Test
    public void zaehlmengenSpeicherMapper_uebernimmtZaehldaten() {
        java.util.List<Artikel> artikelListe = new java.util.ArrayList<>();
        artikelListe.add(new Artikel("ART-1001", "Schraubendreher Kreuz", 125, 80, 90));
        artikelListe.add(new Artikel("ART-1002", "Akkuschrauber 18V", 42, 12, null));

        ZaehlmengenSpeicherAnfrage anfrage = ZaehlmengenSpeicherMapper.mappe(7, "Max", "Erika", artikelListe);

        org.junit.Assert.assertEquals(7, anfrage.getZaehlerlistenId());
        org.junit.Assert.assertEquals("Max", anfrage.getZaehlerName());
        org.junit.Assert.assertEquals("Erika", anfrage.getSchreiberName());
        org.junit.Assert.assertEquals(2, anfrage.getPositionen().size());
        org.junit.Assert.assertEquals("ART-1001", anfrage.getPositionen().get(0).getArtikelnummer());
        org.junit.Assert.assertEquals(Integer.valueOf(90), anfrage.getPositionen().get(0).getZaehlMenge());
    }
}
