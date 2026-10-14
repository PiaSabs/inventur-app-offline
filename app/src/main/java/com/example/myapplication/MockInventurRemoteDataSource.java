package com.example.myapplication;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Diese Datenquelle bildet den aktuellen Prototyp-Stand ab.
// PR: Spaeter wird sie durch die echte ApiInventurRemoteDataSource ersetzt.
public class MockInventurRemoteDataSource implements InventurRemoteDataSource {

    private final List<ZaehlerlisteDto> zaehlerlisten;
    private final Map<Integer, List<ArtikelDto>> artikelNachZaehlerlisteId;

    public MockInventurRemoteDataSource() {
        zaehlerlisten = erstelleZaehlerlisten();
        artikelNachZaehlerlisteId = erstelleArtikelbestand();
    }

    @Override
    public void pruefeVerbindung() {
        // Mock-Modus ist immer verfuegbar.
    }

    @Override
    public List<ZaehlerlisteDto> ladeZaehlerlisten() {
        return new ArrayList<>(zaehlerlisten);
    }

    @Override
    public List<ArtikelDto> ladeArtikel(int zaehlerlistenId) {
        List<ArtikelDto> artikelListe = artikelNachZaehlerlisteId.get(zaehlerlistenId);
        return artikelListe == null ? new ArrayList<>() : new ArrayList<>(artikelListe);
    }

    @Override
    public ZaehlmengenSpeicherAntwort speichereZaehlmengen(ZaehlmengenSpeicherAnfrage anfrage) {
        int gezaehltePositionen = zaehleGezaehltePositionen(anfrage);
        boolean erfolgreich = anfrage != null
                && anfrage.getZaehlerlistenId() > 0
                && anfrage.getZaehlerName() != null
                && !anfrage.getZaehlerName().trim().isEmpty()
                && anfrage.getSchreiberName() != null
                && !anfrage.getSchreiberName().trim().isEmpty()
                && anfrage.getPositionen() != null
                && !anfrage.getPositionen().isEmpty()
                && gezaehltePositionen > 0;

        if (erfolgreich) {
            uebernehmeGespeicherteZaehlmengen(anfrage);
        }

        String meldung = erfolgreich
                ? "Demo lokal gespeichert (keine Serverbestaetigung). Gezaehlte Positionen: " + gezaehltePositionen
                : "PR: Mock-Speichern fehlgeschlagen. Mindestens eine gezaehlte Position wird benoetigt.";

        return new ZaehlmengenSpeicherAntwort(erfolgreich, meldung, "MOCK-" + anfrage.getZaehlerlistenId());
    }

    @Override
    public void markiereZaehlerlisteAlsAbgeschlossen(int zaehlerlistenId) {
        for (int index = 0; index < zaehlerlisten.size(); index++) {
            ZaehlerlisteDto aktuelleZaehlerliste = zaehlerlisten.get(index);
            if (aktuelleZaehlerliste.getId() == zaehlerlistenId) {
                zaehlerlisten.set(index, new ZaehlerlisteDto(
                        aktuelleZaehlerliste.getId(),
                        aktuelleZaehlerliste.getBezeichnung(),
                        aktuelleZaehlerliste.getBlattnummer(),
                        aktuelleZaehlerliste.getBereich(),
                        aktuelleZaehlerliste.getStichtag(),
                        aktuelleZaehlerliste.getLetztesZaehldatum(),
                        aktuelleZaehlerliste.istFaellig(),
                        true,
                        aktuelleZaehlerliste.getGespeicherterZaehlerName(),
                        aktuelleZaehlerliste.getGespeicherterSchreiberName(),
                        aktuelleZaehlerliste.getZaehlTyp()
                ));
                return;
            }
        }
    }

    @Override
    public String holeLetzteDiagnose() {
        return "Lokale Demo aktiv. Keine Serververbindung und keine echte Synchronisierung.";
    }

    private int zaehleGezaehltePositionen(ZaehlmengenSpeicherAnfrage anfrage) {
        if (anfrage == null || anfrage.getPositionen() == null) {
            return 0;
        }

        int gezaehltePositionen = 0;
        for (ZaehlmengenSpeicherPositionDto position : anfrage.getPositionen()) {
            if (position.getZaehlMenge() != null) {
                gezaehltePositionen++;
            }
        }
        return gezaehltePositionen;
    }

    private List<ZaehlerlisteDto> erstelleZaehlerlisten() {
        List<ZaehlerlisteDto> zaehlerlistenListe = new ArrayList<>();
        zaehlerlistenListe.add(new ZaehlerlisteDto(1, "Zaehlerliste Wareneingang", "BL-001", "Wareneingang", "15.04.2026", true, false));
        zaehlerlistenListe.add(new ZaehlerlisteDto(2, "Zaehlerliste Hochregal", "BL-014", "Lager Nord", "28.04.2026", false, false));
        zaehlerlistenListe.add(new ZaehlerlisteDto(3, "Zaehlerliste Versand", "BL-022", "Versandzone", "02.05.2026", true, false));
        zaehlerlistenListe.add(new ZaehlerlisteDto(4, "Zaehlerliste Test Leerstand", "BL-099", "Testbereich", "12.05.2026", false, false));
        return zaehlerlistenListe;
    }

    private Map<Integer, List<ArtikelDto>> erstelleArtikelbestand() {
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
        return artikelbestand;
    }

    private void uebernehmeGespeicherteZaehlmengen(ZaehlmengenSpeicherAnfrage anfrage) {
        List<ArtikelDto> vorhandeneArtikel = artikelNachZaehlerlisteId.get(anfrage.getZaehlerlistenId());
        if (vorhandeneArtikel == null) {
            return;
        }

        Map<String, Integer> zaehlmengenNachArtikelnummer = new HashMap<>();
        for (ZaehlmengenSpeicherPositionDto position : anfrage.getPositionen()) {
            zaehlmengenNachArtikelnummer.put(position.getArtikelnummer(), position.getZaehlMenge());
        }

        List<ArtikelDto> aktualisierteArtikel = new ArrayList<>();
        for (ArtikelDto artikelDto : vorhandeneArtikel) {
            Integer gespeicherteZaehlMenge = zaehlmengenNachArtikelnummer.get(artikelDto.getArtikelnummer());
            aktualisierteArtikel.add(new ArtikelDto(
                    artikelDto.getArtikelnummer(),
                    artikelDto.getBezeichnung(),
                    artikelDto.getIstMenge(),
                    artikelDto.getMindestbestand(),
                    gespeicherteZaehlMenge
            ));
        }

        artikelNachZaehlerlisteId.put(anfrage.getZaehlerlistenId(), aktualisierteArtikel);
    }
}
