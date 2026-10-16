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
        artikelNachZaehlerlisteId = DemoArtikelKatalog.erstelle();
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
                    artikelDto.getPositionsNummer(),
                    artikelDto.getArtikelnummer(),
                    artikelDto.getBezeichnung(),
                    artikelDto.getLagerort(),
                    artikelDto.getIstMenge(),
                    artikelDto.getLetzteZaehlmenge(),
                    artikelDto.getMindestbestand(),
                    gespeicherteZaehlMenge,
                    artikelDto.getAnmerkung()
            ));
        }

        artikelNachZaehlerlisteId.put(anfrage.getZaehlerlistenId(), aktualisierteArtikel);
    }
}
