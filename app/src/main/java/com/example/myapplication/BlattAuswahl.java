package com.example.myapplication;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class BlattAuswahl {
    private BlattAuswahl() { }

    static int findeBlattIndex(List<Zaehlerliste> zaehlerlisten, int zaehlerlistenId) {
        for (int index = 0; index < zaehlerlisten.size(); index++) {
            if (zaehlerlisten.get(index).getId() == zaehlerlistenId) {
                return index;
            }
        }
        return -1;
    }

    // Leere Zähler- und Schreiberfilter passen nur zu ebenfalls leeren Namen.
    static List<Zaehlerliste> wochenBlaetter(List<Zaehlerliste> verfuegbareZaehlerlisten,
            String zaehler, String schreiber, String bezeichnungFilter) {
        List<Zaehlerliste> gefilterteZaehlerlisten = new ArrayList<>();
        String bezeichnung = normalisiereText(bezeichnungFilter);

        for (Zaehlerliste zaehlerliste : verfuegbareZaehlerlisten) {
            if (!"wo".equalsIgnoreCase(zaehlerliste.getZaehlTyp())) {
                continue;
            }
            if (!bezeichnung.isEmpty() && !bezeichnung.equals(normalisiereText(zaehlerliste.getBezeichnung()))) {
                continue;
            }
            if (!texteSindGleich(zaehler, zaehlerliste.getGespeicherterZaehlerName())) {
                continue;
            }
            if (!texteSindGleich(schreiber, zaehlerliste.getGespeicherterSchreiberName())) {
                continue;
            }
            gefilterteZaehlerlisten.add(zaehlerliste);
        }
        return gefilterteZaehlerlisten;
    }

    private static boolean texteSindGleich(String links, String rechts) {
        return normalisiereText(links).equals(normalisiereText(rechts));
    }

    static String normalisiereText(String wert) {
        return wert == null ? "" : wert.trim().toLowerCase(Locale.GERMANY);
    }
}
