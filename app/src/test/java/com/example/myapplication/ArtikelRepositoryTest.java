package com.example.myapplication;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ArtikelRepositoryTest {

    @Test
    public void ladeZaehlerlisten_liefertMockDaten() {
        MockInventurRemoteDataSource repository = new MockInventurRemoteDataSource();

        List<ZaehlerlisteDto> zaehlerlisten = repository.ladeZaehlerlisten();

        assertEquals(4, zaehlerlisten.size());
        assertEquals("Zaehlerliste Wareneingang", zaehlerlisten.get(0).getBezeichnung());
    }

    @Test
    public void speichereZaehlmengen_liefertTrueBeiGueltigerAnfrage() {
        MockInventurRemoteDataSource repository = new MockInventurRemoteDataSource();
        List<Artikel> artikelListe = new ArrayList<>();
        artikelListe.add(new Artikel("ART-1", "Testartikel", 10, 2, 8));

        ZaehlmengenSpeicherAntwort antwort = repository.speichereZaehlmengen(
                ZaehlmengenSpeicherMapper.mappe(1, "Max", "Erika", artikelListe)
        );

        assertTrue(antwort.istErfolgreich());
    }

    @Test
    public void speichereZaehlmengen_liefertFalseBeiLeeremPayload() {
        MockInventurRemoteDataSource repository = new MockInventurRemoteDataSource();

        ZaehlmengenSpeicherAntwort antwort = repository.speichereZaehlmengen(
                ZaehlmengenSpeicherMapper.mappe(1, "Max", "Erika", new ArrayList<>())
        );

        assertFalse(antwort.istErfolgreich());
    }
}
