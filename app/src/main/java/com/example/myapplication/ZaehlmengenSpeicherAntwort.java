package com.example.myapplication;

public class ZaehlmengenSpeicherAntwort {

    private final boolean erfolgreich;
    private final String meldung;
    private final String referenzId;

    public ZaehlmengenSpeicherAntwort(boolean erfolgreich, String meldung, String referenzId) {
        this.erfolgreich = erfolgreich;
        this.meldung = meldung;
        this.referenzId = referenzId;
    }

    public boolean istErfolgreich() {
        return erfolgreich;
    }

    public String getMeldung() {
        return meldung;
    }

    public String getReferenzId() {
        return referenzId;
    }
}
