package com.example.myapplication;

// Dieses Modell beschreibt genau die Daten, die die UI pro Artikel anzeigen soll.
// Wenn spaeter eine API oder SQL-Abfrage angebunden wird, sollten die eingehenden
// Datensaetze in dieselbe Struktur ueberfuehrt werden.
public class Artikel {

    private final int positionsNummer;
    private final String artikelnummer;
    private final String bezeichnung;
    private final String lagerort;
    private final int istMenge;
    private final Integer letzteZaehlmenge;
    private final int mindestbestand;
    private Integer zaehlMenge;
    private String anmerkung;

    public Artikel(String artikelnummer, String bezeichnung, int istMenge, int mindestbestand, Integer zaehlMenge) {
        this(0, artikelnummer, bezeichnung, "", istMenge, null, mindestbestand, zaehlMenge, "");
    }

    public Artikel(
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

    public Artikel(
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

    public Artikel(
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

    public void setZaehlMenge(Integer zaehlMenge) {
        this.zaehlMenge = zaehlMenge;
    }

    public String getAnmerkung() {
        return anmerkung;
    }

    public void setAnmerkung(String anmerkung) {
        this.anmerkung = anmerkung == null ? "" : anmerkung;
    }

    public boolean istGezaehlt() {
        return zaehlMenge != null;
    }

    public boolean istUnterMindestbestand() {
        return zaehlMenge != null && zaehlMenge < mindestbestand;
    }
}
