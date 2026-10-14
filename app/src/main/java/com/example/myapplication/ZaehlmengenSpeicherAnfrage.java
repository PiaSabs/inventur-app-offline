package com.example.myapplication;

import java.util.List;

public class ZaehlmengenSpeicherAnfrage {

    private final int zaehlerlistenId;
    private final String zaehlerName;
    private final String schreiberName;
    private final List<ZaehlmengenSpeicherPositionDto> positionen;

    public ZaehlmengenSpeicherAnfrage(int zaehlerlistenId, String zaehlerName, String schreiberName, List<ZaehlmengenSpeicherPositionDto> positionen) {
        this.zaehlerlistenId = zaehlerlistenId;
        this.zaehlerName = zaehlerName;
        this.schreiberName = schreiberName;
        this.positionen = positionen;
    }

    public int getZaehlerlistenId() {
        return zaehlerlistenId;
    }

    public String getZaehlerName() {
        return zaehlerName;
    }

    public String getSchreiberName() {
        return schreiberName;
    }

    public List<ZaehlmengenSpeicherPositionDto> getPositionen() {
        return positionen;
    }
}
