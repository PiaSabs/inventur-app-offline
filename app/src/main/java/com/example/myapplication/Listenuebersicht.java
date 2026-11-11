package com.example.myapplication;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

// Reiner Anzeigestand; keine Freigabe fuer einen Import von Artikeln oder Ergebnissen.
public final class Listenuebersicht {
    private final List<Zaehlerliste> listen;
    private final Map<Integer, Zaehlerliste> lokaleListen;
    private final Set<Integer> serverIds;
    private final boolean serverstand;
    private final Long letzterErfolgreicherAbrufMillis;

    private Listenuebersicht(List<Zaehlerliste> lokal, List<ZaehlerlisteDto> server) {
        this(lokal, server, null);
    }

    private Listenuebersicht(List<Zaehlerliste> lokal, List<ZaehlerlisteDto> server, Long letzterAbruf) {
        letzterErfolgreicherAbrufMillis = letzterAbruf;
        lokaleListen = new LinkedHashMap<>();
        for (Zaehlerliste liste : lokal) lokaleListen.put(liste.getId(), liste);
        serverIds = new HashSet<>();
        Map<Integer, Zaehlerliste> anzeige = new LinkedHashMap<>();
        serverstand = server != null;
        if (serverstand) {
            for (ZaehlerlisteDto dto : server) {
                if (dto == null || dto.getId() <= 0 || !serverIds.add(dto.getId())) {
                    throw new IllegalArgumentException("Serverlisten sind nicht eindeutig.");
                }
                Zaehlerliste lokalGespeichert = lokaleListen.get(dto.getId());
                anzeige.put(dto.getId(), new Zaehlerliste(dto.getId(), dto.getBezeichnung(),
                        dto.getBlattnummer(), dto.getBereich(), dto.getStichtag(), dto.getLetztesZaehldatum(),
                        dto.istFaellig(), lokalGespeichert != null && lokalGespeichert.istAbgeschlossen(),
                        dto.getGespeicherterZaehlerName(), dto.getGespeicherterSchreiberName(), dto.getZaehlTyp()));
            }
        }
        for (Zaehlerliste liste : lokal) {
            if (!anzeige.containsKey(liste.getId())) anzeige.put(liste.getId(), liste);
        }
        listen = Collections.unmodifiableList(new ArrayList<>(anzeige.values()));
    }

    public static Listenuebersicht vomServer(List<Zaehlerliste> lokal, List<ZaehlerlisteDto> server) {
        if (server == null) throw new IllegalArgumentException("Vollstaendiger Serverstand fehlt.");
        return new Listenuebersicht(lokal, server);
    }

    public static Listenuebersicht lokal(List<Zaehlerliste> lokal) {
        return new Listenuebersicht(lokal, null);
    }

    static Listenuebersicht lokal(List<Zaehlerliste> lokal, Long letzterAbruf) {
        return new Listenuebersicht(lokal, null, letzterAbruf);
    }

    private Listenuebersicht(Listenuebersicht stand, long abrufzeit) {
        listen = stand.listen;
        lokaleListen = stand.lokaleListen;
        serverIds = stand.serverIds;
        serverstand = stand.serverstand;
        letzterErfolgreicherAbrufMillis = abrufzeit;
    }

    Listenuebersicht mitAbrufzeit(long abrufzeit) {
        return new Listenuebersicht(this, abrufzeit);
    }

    public List<Zaehlerliste> getListen() { return listen; }
    public boolean istServerstand() { return serverstand; }
    public Long getLetzterErfolgreicherAbrufMillis() { return letzterErfolgreicherAbrufMillis; }
    public boolean istNurLokal(int id) { return !serverIds.contains(id); }
    public boolean istLokalVerfuegbar(int id) { return lokaleListen.containsKey(id); }

    public List<Zaehlerliste> filtereAuswahl(int blattId, String bisherigeBezeichnung) {
        String bezeichnung = normalisiere(bisherigeBezeichnung);
        for (Zaehlerliste liste : listen) {
            if (liste.getId() == blattId) {
                bezeichnung = normalisiere(liste.getBezeichnung());
                break;
            }
        }
        Zaehlerliste lokalerAnker = lokaleListen.get(blattId);
        String lokaleBezeichnung = normalisiere(lokalerAnker == null
                ? bisherigeBezeichnung : lokalerAnker.getBezeichnung());
        List<Zaehlerliste> auswahl = new ArrayList<>();
        for (Zaehlerliste liste : listen) {
            boolean lokalErhalten = istNurLokal(liste.getId()) && !lokaleBezeichnung.isEmpty()
                    && lokaleBezeichnung.equals(normalisiere(liste.getBezeichnung()));
            if (liste.getId() == blattId || (!bezeichnung.isEmpty()
                    && bezeichnung.equals(normalisiere(liste.getBezeichnung()))) || lokalErhalten) {
                auswahl.add(liste);
            }
        }
        return auswahl;
    }

    private static String normalisiere(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.GERMANY);
    }
}
