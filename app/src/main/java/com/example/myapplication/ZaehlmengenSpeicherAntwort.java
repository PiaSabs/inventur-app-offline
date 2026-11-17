package com.example.myapplication;

public class ZaehlmengenSpeicherAntwort {

    private final boolean erfolgreich;
    private final String meldung;
    private final String referenzId;
    private int uebernommen, gesamt, geschrieben, gleicherWert;
    private boolean wiederholbar;
    private String benutzerMeldung;

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

    public ZaehlmengenSpeicherAntwort mitMengenbericht(int uebernommen, int gesamt,
            int geschrieben, int gleicherWert, java.util.Map<Integer,String> offen, boolean wiederholbar) {
        this.uebernommen=uebernommen; this.gesamt=gesamt;
        this.geschrieben=geschrieben; this.gleicherWert=gleicherWert;
        this.wiederholbar=wiederholbar;
        StringBuilder text=new StringBuilder(gesamt==0 ? erfolgreich ? "Keine neuen Änderungen vorhanden."
                : "Die Speicherung konnte nicht bestätigt werden. Ihre Änderungen bleiben auf dem Tablet."
                : uebernommen+"/"+gesamt+" übernommen");
        if (gleicherWert>0) text.append("\nDavon bereits mit gleichem Wert vorhanden und jetzt bestätigt: ").append(gleicherWert);
        for (java.util.Map.Entry<Integer,String> eintrag:offen.entrySet()) {
            text.append("\nPosition ").append(eintrag.getKey()).append(" – ").append(eintrag.getValue());
        }
        this.benutzerMeldung=text.toString();
        return this;
    }

    public int getUebernommen() { return uebernommen; }
    public int getGesamt() { return gesamt; }
    public int getGeschrieben() { return geschrieben; }
    public int getGleicherWert() { return gleicherWert; }
    public boolean istWiederholbar() { return wiederholbar; }
    public ZaehlmengenSpeicherAntwort mitOffenenEingaben() {
        ZaehlmengenSpeicherAntwort antwort=new ZaehlmengenSpeicherAntwort(false,
                meldung+"\nNeue lokale Eingaben nach dem Mengenabgleich.",referenzId);
        antwort.uebernommen=uebernommen; antwort.gesamt=gesamt;
        antwort.geschrieben=geschrieben; antwort.gleicherWert=gleicherWert;
        antwort.wiederholbar=true;
        antwort.benutzerMeldung=getBenutzerMeldung()+"\nNeue Eingaben sind noch nicht übernommen.";
        return antwort;
    }
    public String getBenutzerMeldung() {
        return benutzerMeldung!=null ? benutzerMeldung : erfolgreich
                ? "Keine neuen Änderungen vorhanden." : "Die Speicherung konnte nicht bestätigt werden. Ihre Änderungen bleiben auf dem Tablet.";
    }
    public String getTitel() { return erfolgreich ? "Änderungen übernommen" : "Nicht alle Änderungen übernommen"; }
}
