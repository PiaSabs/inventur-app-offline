package com.example.myapplication;

import org.junit.Test;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

public class DemoArtikelTest {
    @Test
    public void jedeListeHat25EindeutigePositionenUndAlleMengenfaelle() {
        MockInventurRemoteDataSource demo = new MockInventurRemoteDataSource();
        Set<String> alleNummern = new HashSet<>();
        for (ZaehlerlisteDto liste : demo.ladeZaehlerlisten()) {
            List<ArtikelDto> artikel = demo.ladeArtikel(liste.getId());
            assertEquals(25, artikel.size());
            Set<Integer> positionen = new HashSet<>();
            boolean offen = false, nullmenge = false, positiv = false;
            for (ArtikelDto zeile : artikel) {
                assertTrue(zeile.getPositionsNummer() > 0);
                assertTrue(positionen.add(zeile.getPositionsNummer()));
                assertTrue(alleNummern.add(zeile.getArtikelnummer()));
                Integer menge = zeile.getZaehlMenge();
                offen |= menge == null;
                nullmenge |= menge != null && menge == 0;
                positiv |= menge != null && menge > 0;
            }
            assertTrue(offen && nullmenge && positiv);
        }
    }
}
