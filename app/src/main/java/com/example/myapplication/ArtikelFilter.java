package com.example.myapplication;

final class ArtikelFilter {
    static final int FILTER_ALLE = 1;
    static final int FILTER_OFFEN = 2;
    static final int FILTER_GEZAEHLT = 3;
    static final int FILTER_UNTER_MINDESTBESTAND = 4;

    private ArtikelFilter() { }

    static String normalisiereSuchbegriff(String suchbegriff) {
        return suchbegriff == null ? "" : suchbegriff.trim().toLowerCase();
    }

    static boolean passtZumFilter(Artikel artikel, int filterModus) {
        if (filterModus == FILTER_OFFEN) {
            return !artikel.istGezaehlt();
        }
        if (filterModus == FILTER_GEZAEHLT) {
            return artikel.istGezaehlt();
        }
        if (filterModus == FILTER_UNTER_MINDESTBESTAND) {
            return artikel.istUnterMindestbestand();
        }
        return true;
    }

    static boolean passtZumSuchbegriff(Artikel artikel, String suchbegriff) {
        if (suchbegriff.isEmpty()) {
            return true;
        }
        return enthaeltSuchbegriff(artikel.getArtikelnummer(), suchbegriff)
                || enthaeltSuchbegriff(artikel.getBezeichnung(), suchbegriff)
                || enthaeltSuchbegriff(artikel.getLagerort(), suchbegriff)
                || String.valueOf(artikel.getPositionsNummer()).contains(suchbegriff);
    }

    private static boolean enthaeltSuchbegriff(String wert, String suchbegriff) {
        return wert != null && wert.toLowerCase().contains(suchbegriff);
    }
}
