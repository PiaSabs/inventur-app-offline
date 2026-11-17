package com.example.myapplication;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class SqlPositionsvergleich {
    private int bekannt, unbekannt, geaendert, fehlend, unklar;
    private boolean abrufErfolgreich = true;

    public static SqlPositionsvergleich nichtVerfuegbar() {
        SqlPositionsvergleich ergebnis = new SqlPositionsvergleich();
        ergebnis.abrufErfolgreich = false;
        return ergebnis;
    }

    public static SqlPositionsvergleich vergleiche(int blatt, List<Artikel> lokal, List<SqlPosition> server) {
        if (lokal == null || server == null) return nichtVerfuegbar();
        SqlPositionsvergleich ergebnis = new SqlPositionsvergleich();
        List<SqlPosition> bestand = new ArrayList<>();
        for (Artikel a : lokal) {
            bestand.add(new SqlPosition(a.getSqlPositionsId(), a.getQuellBlattnummer(),
                    a.getPositionsNummer(), a.getArtikelnummer()));
        }
        Set<SqlPosition> lokaleFehler = ungueltige(blatt, bestand);
        Set<SqlPosition> serverFehler = ungueltige(blatt, server);
        Map<Long, SqlPosition> l = eindeutige(bestand, lokaleFehler);
        Map<Long, SqlPosition> s = eindeutige(server, serverFehler);
        // Eine neue ID an einer belegten Position ist keine nachgewiesene Neuanlage.
        Set<SqlPosition> kollisionen = new HashSet<>();
        for (SqlPosition neu : s.values()) {
            if (l.containsKey(neu.id)) continue;
            for (SqlPosition alt : l.values()) {
                if (Objects.equals(neu.position, alt.position)
                        || Objects.equals(neu.artikelnummer, alt.artikelnummer)) {
                    kollisionen.add(neu);
                    if (!s.containsKey(alt.id)) kollisionen.add(alt);
                }
            }
        }
        ergebnis.unklar = lokaleFehler.size() + serverFehler.size() + kollisionen.size();
        for (SqlPosition p : l.values()) {
            if (kollisionen.contains(p)) continue;
            SqlPosition aktuell = s.get(p.id);
            if (aktuell != null) {
                if (Objects.equals(p.position, aktuell.position)
                        && Objects.equals(p.artikelnummer, aktuell.artikelnummer)) ergebnis.bekannt++;
                else ergebnis.geaendert++;
            } else if (serverFehler.isEmpty()) ergebnis.fehlend++;
            else ergebnis.unklar++;
        }
        for (SqlPosition p : s.values()) {
            if (l.containsKey(p.id) || kollisionen.contains(p)) continue;
            if (lokaleFehler.isEmpty()) ergebnis.unbekannt++;
            else ergebnis.unklar++;
        }
        return ergebnis;
    }

    private static Set<SqlPosition> ungueltige(int blatt, List<SqlPosition> positionen) {
        Map<Long, Integer> ids = new HashMap<>();
        Map<Integer, Integer> nummern = new HashMap<>();
        for (SqlPosition p : positionen) {
            if (p == null) continue;
            ids.put(p.id, ids.getOrDefault(p.id, 0) + 1);
            nummern.put(p.position, nummern.getOrDefault(p.position, 0) + 1);
        }
        Set<SqlPosition> fehler = new HashSet<>();
        for (SqlPosition p : positionen) {
            if (p == null || p.id == null || p.id <= 0 || !Objects.equals(p.blatt, blatt)
                    || p.position == null || p.position <= 0 || p.artikelnummer == null
                    || p.artikelnummer.trim().isEmpty() || ids.get(p.id) > 1 || nummern.get(p.position) > 1) {
                fehler.add(p);
            }
        }
        return fehler;
    }

    private static Map<Long, SqlPosition> eindeutige(List<SqlPosition> positionen, Set<SqlPosition> fehler) {
        Map<Long, SqlPosition> ergebnis = new HashMap<>();
        for (SqlPosition p : positionen) if (!fehler.contains(p)) ergebnis.put(p.id, p);
        return ergebnis;
    }

    public int getBekannt() { return bekannt; }
    public int getUnbekannt() { return unbekannt; }
    public int getGeaendert() { return geaendert; }
    public int getFehlend() { return fehlend; }
    public int getUnklar() { return unklar; }
    public boolean istAbrufErfolgreich() { return abrufErfolgreich; }

    public String getAnzeige() {
        if (!abrufErfolgreich) return "SQL-Positionsprüfung nicht verfügbar – Zuordnung ungeklärt. Lokaler Bestand bleibt nutzbar.";
        String grenze = "\nVergleich des aktuellen Positionsbestands; kein Zähldurchgangsabgleich.";
        if (unbekannt + geaendert + fehlend + unklar == 0) return "Keine Positionsänderungen erkannt" + grenze;
        return "SQL-Positionsänderungen gefunden – Prüfung erforderlich"
                + "\nNeue oder unbekannte SQL-Positionen: " + unbekannt
                + " · Geänderte Positionen: " + geaendert
                + "\nNicht mehr vorhandene Positionen: " + fehlend
                + " · Unklare Zuordnungen: " + unklar
                + (unklar > 0 ? "\nZuordnung ungeklärt" : "") + grenze;
    }
}
