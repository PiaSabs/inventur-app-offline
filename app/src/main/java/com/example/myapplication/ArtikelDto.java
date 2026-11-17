package com.example.myapplication;

public class ArtikelDto {

    private final int positionsNummer;
    private final Long sqlPositionsId;
    private final Integer quellBlattnummer;
    private final SqlServerMengenSchreibversuch.Ausgangsstand sqlErgebnis;
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
        this(positionsNummer, artikelnummer, bezeichnung, lagerort, istMenge, letzteZaehlmenge,
                mindestbestand, zaehlMenge, anmerkung, null, null);
    }

    public ArtikelDto(int positionsNummer, String artikelnummer, String bezeichnung, String lagerort,
            int istMenge, Integer letzteZaehlmenge, int mindestbestand, Integer zaehlMenge,
            String anmerkung, Long sqlPositionsId, Integer quellBlattnummer) {
        boolean zugeordnet = sqlPositionsId != null && sqlPositionsId > 0
                && quellBlattnummer != null && quellBlattnummer > 0;
        this.sqlPositionsId = zugeordnet ? sqlPositionsId : null;
        this.quellBlattnummer = zugeordnet ? quellBlattnummer : null;
        this.sqlErgebnis = null;
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

    private ArtikelDto(ArtikelDto basis,SqlServerMengenSchreibversuch.Ausgangsstand ergebnis) {
        if(ergebnis==null || basis.sqlPositionsId==null || basis.quellBlattnummer==null
                || ergebnis.positionsId!=basis.sqlPositionsId || ergebnis.blatt!=basis.quellBlattnummer
                || ergebnis.position!=basis.positionsNummer || !ergebnis.artikel.equals(basis.artikelnummer))
            throw new IllegalArgumentException("SQL-Ergebnis gehoert nicht zur Artikelposition.");
        sqlPositionsId=basis.sqlPositionsId;quellBlattnummer=basis.quellBlattnummer;sqlErgebnis=ergebnis;
        positionsNummer=basis.positionsNummer;artikelnummer=basis.artikelnummer;bezeichnung=basis.bezeichnung;
        lagerort=basis.lagerort;istMenge=basis.istMenge;letzteZaehlmenge=basis.letzteZaehlmenge;
        mindestbestand=basis.mindestbestand;anmerkung=basis.anmerkung;
        zaehlMenge=ergebnis.menge==null?null:ergebnis.menge.intValueExact();
    }
    public ArtikelDto mitSqlErgebnis(SqlServerMengenSchreibversuch.Ausgangsstand ergebnis) {return new ArtikelDto(this,ergebnis);}
    public SqlServerMengenSchreibversuch.Ausgangsstand getSqlErgebnis() {return sqlErgebnis;}

    public Long getSqlPositionsId() { return sqlPositionsId; }

    public Integer getQuellBlattnummer() { return quellBlattnummer; }

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
