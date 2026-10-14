package com.example.myapplication;

public class ZaehlerlisteDto {

    private final int id;
    private final String bezeichnung;
    private final String blattnummer;
    private final String bereich;
    private final String stichtag;
    private final String letztesZaehldatum;
    private final boolean faellig;
    private final boolean abgeschlossen;
    private final String gespeicherterZaehlerName;
    private final String gespeicherterSchreiberName;
    private final String zaehlTyp;

    public ZaehlerlisteDto(int id, String bezeichnung, String blattnummer, String bereich, String letztesZaehldatum, boolean faellig, boolean abgeschlossen) {
        this(id, bezeichnung, blattnummer, bereich, letztesZaehldatum, letztesZaehldatum, faellig, abgeschlossen, "", "", "");
    }

    public ZaehlerlisteDto(
            int id,
            String bezeichnung,
            String blattnummer,
            String bereich,
            String letztesZaehldatum,
            boolean faellig,
            boolean abgeschlossen,
            String gespeicherterZaehlerName,
            String gespeicherterSchreiberName,
            String zaehlTyp
    ) {
        this(id, bezeichnung, blattnummer, bereich, letztesZaehldatum, letztesZaehldatum, faellig, abgeschlossen, gespeicherterZaehlerName, gespeicherterSchreiberName, zaehlTyp);
    }

    public ZaehlerlisteDto(
            int id,
            String bezeichnung,
            String blattnummer,
            String bereich,
            String stichtag,
            String letztesZaehldatum,
            boolean faellig,
            boolean abgeschlossen,
            String gespeicherterZaehlerName,
            String gespeicherterSchreiberName,
            String zaehlTyp
    ) {
        this.id = id;
        this.bezeichnung = bezeichnung;
        this.blattnummer = blattnummer;
        this.bereich = bereich;
        this.stichtag = stichtag == null ? "" : stichtag;
        this.letztesZaehldatum = letztesZaehldatum;
        this.faellig = faellig;
        this.abgeschlossen = abgeschlossen;
        this.gespeicherterZaehlerName = gespeicherterZaehlerName == null ? "" : gespeicherterZaehlerName;
        this.gespeicherterSchreiberName = gespeicherterSchreiberName == null ? "" : gespeicherterSchreiberName;
        this.zaehlTyp = zaehlTyp == null ? "" : zaehlTyp;
    }

    public int getId() {
        return id;
    }

    public String getBezeichnung() {
        return bezeichnung;
    }

    public String getBlattnummer() {
        return blattnummer;
    }

    public String getBereich() {
        return bereich;
    }

    public String getStichtag() {
        return stichtag;
    }

    public String getLetztesZaehldatum() {
        return letztesZaehldatum;
    }

    public boolean istFaellig() {
        return faellig;
    }

    public boolean istAbgeschlossen() {
        return abgeschlossen;
    }

    public String getGespeicherterZaehlerName() {
        return gespeicherterZaehlerName;
    }

    public String getGespeicherterSchreiberName() {
        return gespeicherterSchreiberName;
    }

    public String getZaehlTyp() {
        return zaehlTyp;
    }
}
