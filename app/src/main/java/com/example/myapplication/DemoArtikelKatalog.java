package com.example.myapplication;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class DemoArtikelKatalog {
    private DemoArtikelKatalog() { }

    static Map<Integer, List<ArtikelDto>> erstelle() {
        Map<Integer, List<ArtikelDto>> artikelbestand = new HashMap<>();

        List<ArtikelDto> wareneingang = new ArrayList<>();
        wareneingang.add(new ArtikelDto("ART-1001", "Schraubendreher Kreuz", 125, 80, 92));
        wareneingang.add(new ArtikelDto("ART-1002", "Akkuschrauber 18V", 42, 12, 8));
        wareneingang.add(new ArtikelDto("ART-1006", "Transportkiste Klein", 31, 15, null));
        wareneingang.add(new ArtikelDto("ART-1010", "Arbeitshandschuhe", 54, 20, 24));
        artikelbestand.put(1, wareneingang);

        List<ArtikelDto> hochregal = new ArrayList<>();
        hochregal.add(new ArtikelDto("ART-1003", "Unterlegscheibe M8", 860, 300, null));
        hochregal.add(new ArtikelDto("ART-1005", "Lagerbox Gross", 64, 20, null));
        hochregal.add(new ArtikelDto("ART-1007", "Kabelbinder Set", 210, 120, 140));
        hochregal.add(new ArtikelDto("ART-1011", "Sicherungsring Set", 19, 10, 6));
        artikelbestand.put(2, hochregal);

        List<ArtikelDto> versand = new ArrayList<>();
        versand.add(new ArtikelDto("ART-1004", "Sicherheitshelm Blau", 18, 10, 11));
        versand.add(new ArtikelDto("ART-1008", "Versandetiketten", 480, 150, null));
        versand.add(new ArtikelDto("ART-1009", "Packband Transparent", 73, 30, 21));
        versand.add(new ArtikelDto("ART-1012", "Warnweste Gelb", 33, 12, null));
        artikelbestand.put(3, versand);

        artikelbestand.put(4, new ArrayList<>());
        for (Map.Entry<Integer, List<ArtikelDto>> eintrag : artikelbestand.entrySet()) {
            List<ArtikelDto> positionen = new ArrayList<>();
            for (ArtikelDto artikel : eintrag.getValue()) {
                positionen.add(new ArtikelDto(positionen.size() + 1, artikel.getArtikelnummer(),
                        artikel.getBezeichnung(), "Demo-Regal 1", artikel.getIstMenge(),
                        artikel.getMindestbestand(), artikel.getZaehlMenge()));
            }
            while (positionen.size() < 25) {
                int position = positionen.size() + 1;
                Integer menge = position % 3 == 0 ? null : (position % 3 == 1 ? 0 : 12);
                positionen.add(new ArtikelDto(position,
                        String.format(java.util.Locale.ROOT, "DEMO-%02d-%03d", eintrag.getKey(), position),
                        "Demo-Testartikel " + eintrag.getKey() + " / " + position,
                        "Demo-Regal " + ((position - 1) / 5 + 1), 30 + position, 5, menge));
            }
            eintrag.setValue(positionen);
        }
        return artikelbestand;
    }
}
