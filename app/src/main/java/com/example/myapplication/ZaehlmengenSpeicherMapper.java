package com.example.myapplication;

import java.util.ArrayList;
import java.util.List;

public final class ZaehlmengenSpeicherMapper {

    private ZaehlmengenSpeicherMapper() {
    }

    public static ZaehlmengenSpeicherAnfrage mappe(int zaehlerlistenId, String zaehlerName, String schreiberName, List<Artikel> artikelListe) {
        List<ZaehlmengenSpeicherPositionDto> positionen = new ArrayList<>();
        if (artikelListe != null) {
            for (Artikel artikel : artikelListe) {
                positionen.add(new ZaehlmengenSpeicherPositionDto(
                        artikel.getPositionsNummer(),
                        artikel.getArtikelnummer(),
                        artikel.getZaehlMenge()
                ));
            }
        }

        return new ZaehlmengenSpeicherAnfrage(
                zaehlerlistenId,
                zaehlerName,
                schreiberName,
                positionen
        );
    }
}
