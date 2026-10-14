package com.example.myapplication;

import java.util.ArrayList;
import java.util.List;

public final class ZaehlerlisteMapper {

    private ZaehlerlisteMapper() {
    }

    public static List<Zaehlerliste> mappeListe(List<ZaehlerlisteDto> dtos) {
        List<Zaehlerliste> zaehlerlisten = new ArrayList<>();
        if (dtos == null) {
            return zaehlerlisten;
        }

        for (ZaehlerlisteDto dto : dtos) {
            zaehlerlisten.add(mappe(dto));
        }
        return zaehlerlisten;
    }

    public static Zaehlerliste mappe(ZaehlerlisteDto dto) {
        return new Zaehlerliste(
                dto.getId(),
                dto.getBezeichnung(),
                dto.getBlattnummer(),
                dto.getBereich(),
                dto.getStichtag(),
                dto.getLetztesZaehldatum(),
                dto.istFaellig(),
                dto.istAbgeschlossen(),
                dto.getGespeicherterZaehlerName(),
                dto.getGespeicherterSchreiberName(),
                dto.getZaehlTyp()
        );
    }
}
