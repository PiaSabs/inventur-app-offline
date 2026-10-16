package com.example.myapplication;

import java.util.ArrayList;
import java.util.List;

final class ArtikelSnapshots {
    private ArtikelSnapshots() { }

    static List<Artikel> erstelle(List<Artikel> artikelListe) {
        List<Artikel> snapshot = new ArrayList<>();
        for (Artikel artikel : artikelListe) {
            // Eigene Artikelobjekte halten Menge und Notiz vom weiteren Bearbeiten getrennt.
            snapshot.add(new Artikel(
                    artikel.getPositionsNummer(),
                    artikel.getArtikelnummer(),
                    artikel.getBezeichnung(),
                    artikel.getLagerort(),
                    artikel.getIstMenge(),
                    artikel.getLetzteZaehlmenge(),
                    artikel.getMindestbestand(),
                    artikel.getZaehlMenge(),
                    artikel.getAnmerkung()
            ));
        }
        return snapshot;
    }
}
