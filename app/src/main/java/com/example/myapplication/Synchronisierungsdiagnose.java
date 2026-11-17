package com.example.myapplication;

// Zaehlt lokale Sperrgruende, ohne eine Serverquittierung abzuleiten.
public final class Synchronisierungsdiagnose {
    private final boolean verfuegbar;
    private final long warteschlange, positionen, koepfe, altpositionen;
    private final long positionsrevisionen, kopfrevisionen, revisionenOhneKennzeichen, serverstaende;

    public Synchronisierungsdiagnose(long warteschlange, long positionen, long koepfe, long altpositionen,
            long positionsrevisionen, long kopfrevisionen, long revisionenOhneKennzeichen, long serverstaende) {
        this(true, warteschlange, positionen, koepfe, altpositionen, positionsrevisionen,
                kopfrevisionen, revisionenOhneKennzeichen, serverstaende);
    }

    private Synchronisierungsdiagnose(boolean verfuegbar, long warteschlange, long positionen, long koepfe,
            long altpositionen, long positionsrevisionen, long kopfrevisionen,
            long revisionenOhneKennzeichen, long serverstaende) {
        this.verfuegbar = verfuegbar;
        this.warteschlange = warteschlange;
        this.positionen = positionen;
        this.koepfe = koepfe;
        this.altpositionen = altpositionen;
        this.positionsrevisionen = positionsrevisionen;
        this.kopfrevisionen = kopfrevisionen;
        this.revisionenOhneKennzeichen = revisionenOhneKennzeichen;
        this.serverstaende = serverstaende;
    }

    public static Synchronisierungsdiagnose nichtVerfuegbar() {
        return new Synchronisierungsdiagnose(false, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public boolean istVerfuegbar() { return verfuegbar; }
    public long getWarteschlange() { return warteschlange; }
    public long getGeaendertePositionen() { return positionen; }
    public long getGeaenderteKoepfe() { return koepfe; }
    public long getUngeklaerteAltpositionen() { return altpositionen; }
    public long getPositivePositionsrevisionen() { return positionsrevisionen; }
    public long getPositiveKopfrevisionen() { return kopfrevisionen; }
    public long getRevisionenOhneKennzeichen() { return revisionenOhneKennzeichen; }
    public long getGespeicherteServerstaende() { return serverstaende; }
    public boolean hatOffeneAenderungen() { return !verfuegbar || warteschlange > 0 || positionen > 0 || koepfe > 0; }

    public String getKurztext() {
        if (!verfuegbar) return "Lokaler Sperrzustand konnte nicht gelesen werden. Keine Freigabe möglich.";
        if (positionen + koepfe > 0) return positionen
                + (positionen == 1 ? " geänderte Position und " : " geänderte Positionen und ") + koepfe
                + (koepfe == 1 ? " Kopf-/Abschlussänderung" : " Kopf-/Abschlussänderungen")
                + " warten auf Serverbestätigung.";
        if (warteschlange > 0) return "Offene Übertragungsaufträge: " + warteschlange;
        return "Keine offenen Änderungskennzeichen oder Übertragungsaufträge.";
    }

    public String getDetails() {
        if (!verfuegbar) return getKurztext();
        return getKurztext() + "\nWarteschlangeneinträge: " + warteschlange
                + "\nUngeklärte Altpositionen: " + altpositionen
                + "\nPositive Positionsrevisionen: " + positionsrevisionen
                + "\nPositive Kopfrevisionen: " + kopfrevisionen
                + "\nRevisionen ohne Änderungskennzeichen: " + revisionenOhneKennzeichen
                + "\nDatensätze mit gespeichertem Serverstand: " + serverstaende
                + "\nRevisionsnummern und gespeicherte Serverstände allein sind keine Übertragungsbestätigung.";
    }
}
