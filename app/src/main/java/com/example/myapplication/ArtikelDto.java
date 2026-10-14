package com.example.myapplication;

public class ArtikelDto {

    private final int positionsNummer;
    private final String artikelnummer;
    private final String bezeichnung;
    private final String lagerort;
    private final int istMenge;
    private final Integer letzteZaehlmenge;
    private final int mindestbestand;
    private final Integer zaehlMenge;
    private final String anmerkung;

    public ArtikelDto(String artikelnummer, String bezeichnung, int istMenge, int mindestbestand, Integer zaehlMenge) {
        this(0, artikelnummer, bezeichnung, "", istMenge, null, mindestbestand, zaehlMenge, "");
    }

    public ArtikelDto(
            int positionsNummer,
            String artikelnummer,
            String bezeichnung,
            String lagerort,
            int istMenge,
            int mindestbestand,
            Integer zaehlMenge
    ) {
        this(positionsNummer, artikelnummer, bezeichnung, lagerort, istMenge, null, mindestbestand, zaehlMenge, "");
    }

    public ArtikelDto(
            int positionsNummer,
            String artikelnummer,
            String bezeichnung,
            String lagerort,
            int istMenge,
            Integer letzteZaehlmenge,
            int mindestbestand,
            Integer zaehlMenge
    ) {
        this(positionsNummer, artikelnummer, bezeichnung, lagerort, istMenge, letzteZaehlmenge, mindestbestand, zaehlMenge, "");
    }

    public ArtikelDto(
            int positionsNummer,
            String artikelnummer,
            String bezeichnung,
            String lagerort,
            int istMenge,
            Integer letzteZaehlmenge,
            int mindestbestand,
            Integer zaehlMenge,
            String anmerkung
    ) {
        this.positionsNummer = positionsNummer;
        this.artikelnummer = artikelnummer;
        this.bezeichnung = bezeichnung;
        this.lagerort = lagerort == null ? "" : lagerort;
        this.istMenge = istMenge;
        this.letzteZaehlmenge = letzteZaehlmenge;
        this.mindestbestand = mindestbestand;
        this.zaehlMenge = zaehlMenge;
        this.anmerkung = anmerkung == null ? "" : anmerkung;
    }

    public int getPositionsNummer() {
        return positionsNummer;
    }

    public String getArtikelnummer() {
        return artikelnummer;
    }

    public String getBezeichnung() {
        return bezeichnung;
    }

    public String getLagerort() {
        return lagerort;
    }

    public int getIstMenge() {
        return istMenge;
    }

    public Integer getLetzteZaehlmenge() {
        return letzteZaehlmenge;
    }

    public int getMindestbestand() {
        return mindestbestand;
    }

    public Integer getZaehlMenge() {
        return zaehlMenge;
    }

    public String getAnmerkung() {
        return anmerkung;
    }
}
