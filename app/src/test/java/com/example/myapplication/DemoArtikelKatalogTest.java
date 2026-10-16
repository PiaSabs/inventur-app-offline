package com.example.myapplication;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.*;

public class DemoArtikelKatalogTest {
    // Prüfsummen erfassen alle neun Felder und die Reihenfolge des bisherigen Katalogs.
    private static final String[] ERWARTETE_KATALOGE = {
            "da769186f9ca572ef6291f0bd8010bd60150e55a42f1046dd47f30350e10b01d",
            "cf1e228ca98bf00e15f569ce96b587b2778c730846936316e4c5c8370063063c",
            "b5bcd9a83951380d7717b6c56da0eead8841289f070732b9913c1a1356305105",
            "a65e1b4132a2a46340252d33daa0e37088c0ac7698e6656351c768a4445f00c1"
    };

    @Test
    public void alleHundertArtikelEntsprechenDemBisherigenKatalog() throws Exception {
        Map<Integer, List<ArtikelDto>> katalog = DemoArtikelKatalog.erstelle();
        assertEquals(new HashSet<>(Arrays.asList(1, 2, 3, 4)), katalog.keySet());
        for (int id = 1; id <= 4; id++) {
            assertEquals(25, katalog.get(id).size());
            assertEquals("Liste " + id, ERWARTETE_KATALOGE[id - 1], fingerprint(katalog.get(id)));
        }
    }

    @Test
    public void mockDatenquelleLiefertUnveraenderteKatalogdaten() throws Exception {
        MockInventurRemoteDataSource demo = new MockInventurRemoteDataSource();
        List<ZaehlerlisteDto> listen = demo.ladeZaehlerlisten();
        assertEquals(4, listen.size());
        for (int index = 0; index < listen.size(); index++) {
            int id = listen.get(index).getId();
            assertEquals(index + 1, id);
            assertEquals(ERWARTETE_KATALOGE[index], fingerprint(demo.ladeArtikel(id)));
        }
        assertTrue(demo.ladeArtikel(999).isEmpty());
    }

    @Test
    public void jederKatalogErzeugtEigeneVeraenderbareListen() throws Exception {
        Map<Integer, List<ArtikelDto>> erster = DemoArtikelKatalog.erstelle();
        Map<Integer, List<ArtikelDto>> zweiter = DemoArtikelKatalog.erstelle();
        assertNotSame(erster, zweiter);
        for (int id = 1; id <= 4; id++) {
            assertNotSame(erster.get(id), zweiter.get(id));
            erster.get(id).clear();
            assertEquals(25, zweiter.get(id).size());
            assertEquals(ERWARTETE_KATALOGE[id - 1], fingerprint(zweiter.get(id)));
        }
        erster.remove(1);
        assertTrue(zweiter.containsKey(1));
    }

    @Test
    public void mockSpeichernBleibtVomNeuenKatalogUndAnderenInstanzenGetrennt() throws Exception {
        MockInventurRemoteDataSource demo = new MockInventurRemoteDataSource();
        MockInventurRemoteDataSource andere = new MockInventurRemoteDataSource();
        List<ArtikelDto> vorher = demo.ladeArtikel(1);
        List<ZaehlmengenSpeicherPositionDto> positionen = new ArrayList<>();
        for (int index = 0; index < vorher.size(); index++) {
            ArtikelDto a = vorher.get(index);
            positionen.add(new ZaehlmengenSpeicherPositionDto(a.getPositionsNummer(),
                    a.getArtikelnummer(), index == 0 ? Integer.valueOf(0) : a.getZaehlMenge()));
        }
        assertTrue(demo.speichereZaehlmengen(new ZaehlmengenSpeicherAnfrage(
                1, "Testzaehler", "Testschreiber", positionen)).istErfolgreich());
        assertEquals(Integer.valueOf(0), demo.ladeArtikel(1).get(0).getZaehlMenge());
        assertEquals(Integer.valueOf(92), vorher.get(0).getZaehlMenge());
        assertEquals(ERWARTETE_KATALOGE[0], fingerprint(andere.ladeArtikel(1)));
        assertEquals(ERWARTETE_KATALOGE[0], fingerprint(DemoArtikelKatalog.erstelle().get(1)));
        demo.ladeArtikel(1).clear();
        assertEquals(25, demo.ladeArtikel(1).size());
        assertEquals(Integer.valueOf(0), demo.ladeArtikel(1).get(0).getZaehlMenge());
    }

    @Test
    public void artikeldatenBleibenUnabhaengigVonDerGeraetesprache() throws Exception {
        Locale vorher = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            Map<Integer, List<ArtikelDto>> katalog = DemoArtikelKatalog.erstelle();
            for (int id = 1; id <= 4; id++) {
                assertEquals(ERWARTETE_KATALOGE[id - 1], fingerprint(katalog.get(id)));
            }
        } finally {
            Locale.setDefault(vorher);
        }
    }

    private static String fingerprint(List<ArtikelDto> artikel) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(artikel.size());
            for (ArtikelDto a : artikel) {
                out.writeInt(a.getPositionsNummer());
                text(out, a.getArtikelnummer());
                text(out, a.getBezeichnung());
                text(out, a.getLagerort());
                out.writeInt(a.getIstMenge());
                zahl(out, a.getLetzteZaehlmenge());
                out.writeInt(a.getMindestbestand());
                zahl(out, a.getZaehlMenge());
                text(out, a.getAnmerkung());
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())) {
            hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return hex.toString();
    }

    private static void text(DataOutputStream out, String wert) throws Exception {
        out.writeBoolean(wert != null);
        if (wert != null) out.writeUTF(wert);
    }

    private static void zahl(DataOutputStream out, Integer wert) throws Exception {
        out.writeBoolean(wert != null);
        if (wert != null) out.writeInt(wert);
    }
}
