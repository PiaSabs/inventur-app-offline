package com.example.myapplication;

import java.util.ArrayList;
import java.util.List;

public final class ArtikelMapper {

    private ArtikelMapper() {
    }

    public static List<Artikel> mappeListe(List<ArtikelDto> dtos) {
        List<Artikel> artikelListe = new ArrayList<>();
        if (dtos == null) {
            return artikelListe;
        }

        for (ArtikelDto dto : dtos) {
            artikelListe.add(mappe(dto));
        }
        return artikelListe;
    }

    public static Artikel mappe(ArtikelDto dto) {
        return new Artikel(
                dto.getPositionsNummer(),
                dto.getArtikelnummer(),
                dto.getBezeichnung(),
                dto.getLagerort(),
                dto.getIstMenge(),
                dto.getLetzteZaehlmenge(),
                dto.getMindestbestand(),
                dto.getZaehlMenge(),
                dto.getAnmerkung()
        );
    }
}
