package com.example.myapplication;

public class ZaehlmengenSpeicherPositionDto {

    private final int positionsNummer;
    private final String artikelnummer;
    private final Integer zaehlMenge;

    public ZaehlmengenSpeicherPositionDto(int positionsNummer, String artikelnummer, Integer zaehlMenge) {
        this.positionsNummer = positionsNummer;
        this.artikelnummer = artikelnummer;
        this.zaehlMenge = zaehlMenge;
    }

    public int getPositionsNummer() {
        return positionsNummer;
    }

    public String getArtikelnummer() {
        return artikelnummer;
    }

    public Integer getZaehlMenge() {
        return zaehlMenge;
    }
}
