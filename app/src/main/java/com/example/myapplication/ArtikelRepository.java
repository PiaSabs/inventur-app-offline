package com.example.myapplication;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Das Repository kapselt die Herkunft der Artikeldaten.
// Die Activity kennt dadurch nur fachliche Methoden und muss spaeter nicht
// umgebaut werden, wenn statt Mock-Daten eine echte Schnittstelle genutzt wird.
public class ArtikelRepository {

    private static final long REMOTE_FEHLER_COOLDOWN_MS = 15_000L;

    private final InventurRemoteDataSource remoteDataSource;
    private final LokalerInventurSpeicher lokalerInventurSpeicher;
    private boolean remoteBestandSynchronisiert;
    private boolean automatischeSynchronisierungPausiert;
    private String letzteSynchronisierungsFehlermeldung = "";
    private long naechsterRemoteVersuchAbMillis;

    public ArtikelRepository(InventurRemoteDataSource remoteDataSource, LokalerInventurSpeicher lokalerInventurSpeicher) {
        this.remoteDataSource = remoteDataSource;
        this.lokalerInventurSpeicher = lokalerInventurSpeicher;
    }

    public List<Zaehlerliste> ladeZaehlerlisten() {
        initialisiereOfflineBestandFallsNoetig();
        return lokalerInventurSpeicher.ladeZaehlerlisten();
    }

    public Zaehlerliste ladeZaehlerlisteFuerId(int zaehlerlistenId) {
        initialisiereOfflineBestandFallsNoetig();
        return lokalerInventurSpeicher.ladeZaehlerlisteFuerId(zaehlerlistenId);
    }

    public List<Artikel> ladeArtikel(int zaehlerlistenId) {
        initialisiereOfflineBestandFallsNoetig();
        return lokalerInventurSpeicher.ladeArtikel(zaehlerlistenId);
    }

    public ZaehlmengenSpeicherAntwort speichereZaehlmengen(int zaehlerlistenId, String zaehlerName, String schreiberName, List<Artikel> artikelListe) {
        return speichereZaehlmengenMitSynchronisierung(zaehlerlistenId, zaehlerName, schreiberName, artikelListe);
    }

    public ZaehlmengenSpeicherAntwort speichereZaehlmengenNurLokal(int zaehlerlistenId, String zaehlerName, String schreiberName, List<Artikel> artikelListe) {
        if (!istSpeicherAnfrageGueltig(zaehlerlistenId, artikelListe)) {
            return neueLeereSpeicherAntwort();
        }

        lokalerInventurSpeicher.speichereZaehlmengen(
                zaehlerlistenId,
                zaehlerName,
                schreiberName,
                artikelListe
        );
        lokalerInventurSpeicher.merkeZaehlerlisteFuerSynchronisierung(zaehlerlistenId);
        return new ZaehlmengenSpeicherAntwort(
                true,
                "Lokal gespeichert.",
                null
        );
    }

    public ZaehlmengenSpeicherAntwort speichereZaehlmengenMitSynchronisierung(int zaehlerlistenId, String zaehlerName, String schreiberName, List<Artikel> artikelListe) {
        if (!istSpeicherAnfrageGueltig(zaehlerlistenId, artikelListe)) {
            return neueLeereSpeicherAntwort();
        }

        ZaehlmengenSpeicherAnfrage anfrage = ZaehlmengenSpeicherMapper.mappe(
                zaehlerlistenId,
                zaehlerName,
                schreiberName,
                artikelListe
        );

        lokalerInventurSpeicher.speichereZaehlmengen(
                zaehlerlistenId,
                zaehlerName,
                schreiberName,
                artikelListe
        );
        lokalerInventurSpeicher.merkeZaehlerlisteFuerSynchronisierung(zaehlerlistenId);

        if (!darfRemoteVersuchtWerden()) {
            return new ZaehlmengenSpeicherAntwort(
                    true,
                    "Lokal gespeichert. Datenbank-Synchronisierung ist weiterhin offen und wird spaeter erneut versucht.",
                    null
            );
        }

        try {
            ZaehlmengenSpeicherAntwort antwort = remoteDataSource.speichereZaehlmengen(anfrage);
            if (antwort.istErfolgreich()) {
                merkeRemoteErfolg();
                lokalerInventurSpeicher.entferneZaehlerlisteAusSynchronisierung(zaehlerlistenId);
                synchronisiereAusstehendeAenderungen(true);
                if (!lokalerInventurSpeicher.hatOffeneSynchronisierung()) {
                    letzteSynchronisierungsFehlermeldung = "";
                }
                return new ZaehlmengenSpeicherAntwort(
                        true,
                        "Lokal gespeichert und an die Datenbank uebertragen. " + antwort.getMeldung(),
                        antwort.getReferenzId()
                );
            }
            letzteSynchronisierungsFehlermeldung = antwort.getMeldung();
            return new ZaehlmengenSpeicherAntwort(
                    true,
                    "Lokal gespeichert. Die Datenbank-Synchronisierung ist offen und wird spaeter erneut versucht.",
                    antwort.getReferenzId()
            );
        } catch (RemoteDatenException exception) {
            merkeRemoteFehlschlag();
            letzteSynchronisierungsFehlermeldung = baueSynchronisierungsFehlermeldung(exception);
            return new ZaehlmengenSpeicherAntwort(
                    true,
                    "Lokal gespeichert. Datenbank aktuell nicht erreichbar. Synchronisierung wird spaeter automatisch nachgeholt.",
                    null
            );
        }
    }

    public boolean markiereZaehlerlisteAlsAbgeschlossen(int zaehlerlistenId) {
        if (zaehlerlistenId <= 0) {
            return false;
        }

        boolean lokalAktualisiert = lokalerInventurSpeicher.markiereZaehlerlisteAlsAbgeschlossen(zaehlerlistenId);
        if (!lokalAktualisiert) {
            return false;
        }

        lokalerInventurSpeicher.merkeZaehlerlisteFuerSynchronisierung(zaehlerlistenId);
        if (darfRemoteVersuchtWerden()) {
            synchronisiereAusstehendeAenderungen(true);
        }
        return lokalAktualisiert;
    }

    public boolean markiereZaehlerlisteAlsOffen(int zaehlerlistenId) {
        if (zaehlerlistenId <= 0) {
            return false;
        }
        return lokalerInventurSpeicher.markiereZaehlerlisteAlsOffen(zaehlerlistenId);
    }

    public boolean istGesamteZaehlerlisteAbgeschlossen(int zaehlerlistenId) {
        Zaehlerliste zaehlerliste = lokalerInventurSpeicher.ladeZaehlerlisteFuerId(zaehlerlistenId);
        if (zaehlerliste == null) {
            return false;
        }
        return lokalerInventurSpeicher.sindAlleZaehlerlistenMitBezeichnungAbgeschlossen(
                zaehlerliste.getBezeichnung()
        );
    }

    public void angleicheBeispielWochenlistenZuordnungFuerTest(String zaehlerName, String schreiberName) {
        lokalerInventurSpeicher.angleicheBeispielWochenlistenZuordnungFuerTest(zaehlerName, schreiberName);
    }

    public boolean hatOffeneSynchronisierung() {
        return lokalerInventurSpeicher.hatOffeneSynchronisierung();
    }

    public boolean hatOffeneSynchronisierungFuerZaehlerliste(int zaehlerlistenId) {
        return lokalerInventurSpeicher.hatOffeneSynchronisierungFuerZaehlerliste(zaehlerlistenId);
    }

    public void setzeAutomatischeSynchronisierungPausiert(boolean pausiert) {
        automatischeSynchronisierungPausiert = pausiert;
    }

    public List<LokalerInventurSpeicher.BearbeiterInfo> ladeSupportBearbeiter() {
        initialisiereOfflineBestandFallsNoetig();
        List<String> firmen = new ArrayList<>();
        firmen.add("demo");

        return lokalerInventurSpeicher.ladeBearbeiterFuerFirmenMitTelefon(firmen);
    }

    public String holeLetzteSynchronisierungsFehlermeldung() {
        String diagnose = remoteDataSource.holeLetzteDiagnose();
        boolean hatFehlermeldung = letzteSynchronisierungsFehlermeldung != null
                && !letzteSynchronisierungsFehlermeldung.trim().isEmpty();
        boolean hatDiagnose = diagnose != null && !diagnose.trim().isEmpty();

        if (!hatFehlermeldung && !hatDiagnose) {
            return "Die genaue Ursache konnte noch nicht ermittelt werden.";
        }
        if (!hatFehlermeldung) {
            return diagnose;
        }
        if (!hatDiagnose) {
            return letzteSynchronisierungsFehlermeldung;
        }
        return letzteSynchronisierungsFehlermeldung + "\n\nDiagnose:\n" + diagnose;
    }

    public void pruefeDatenbankverbindungUndSynchronisiereBeiBedarf() {
        if (automatischeSynchronisierungPausiert || !AppContainer.istApiModusAktiv() || !darfRemoteVersuchtWerden()) {
            return;
        }

        try {
            remoteDataSource.pruefeVerbindung();
            merkeRemoteErfolg();

            if (!lokalerInventurSpeicher.hatZaehlerlisten()) {
                initialisiereOfflineBestandFallsNoetig();
                if (!lokalerInventurSpeicher.hatOffeneSynchronisierung()) {
                    letzteSynchronisierungsFehlermeldung = "";
                }
                return;
            }

            synchronisiereAusstehendeAenderungen();
            if (!lokalerInventurSpeicher.hatOffeneSynchronisierung()) {
                letzteSynchronisierungsFehlermeldung = "";
            }
        } catch (RemoteDatenException exception) {
            merkeRemoteFehlschlag();
            letzteSynchronisierungsFehlermeldung = baueSynchronisierungsFehlermeldung(exception);
        }
    }

    public void pruefeDatenbankverbindungUndSynchronisiereSofort() {
        naechsterRemoteVersuchAbMillis = 0L;
        pruefeDatenbankverbindungUndSynchronisiereBeiBedarf();
    }

    public void verarbeiteNetzwerkWiederVerfuegbar() {
        if (automatischeSynchronisierungPausiert || !AppContainer.istApiModusAktiv()) {
            return;
        }
        pruefeDatenbankverbindungUndSynchronisiereBeiBedarf();
    }

    private void initialisiereOfflineBestandFallsNoetig() {
        boolean apiModusAktiv = AppContainer.istApiModusAktiv();
        lokalerInventurSpeicher.initialisiereBeispieldatenFuerHilfstabellenFallsLeer();
        boolean hatLokaleZaehlerlisten = lokalerInventurSpeicher.hatZaehlerlisten();

        if (apiModusAktiv && hatLokaleZaehlerlisten && !automatischeSynchronisierungPausiert) {
            synchronisiereAusstehendeAenderungen();
        }
        if (apiModusAktiv && hatLokaleZaehlerlisten) {
            return;
        }

        if (!apiModusAktiv && hatLokaleZaehlerlisten) {
            return;
        }

        try {
            List<ZaehlerlisteDto> zaehlerlisten = remoteDataSource.ladeZaehlerlisten();
            Map<Integer, List<ArtikelDto>> artikelNachZaehlerlistenId = new LinkedHashMap<>();
            for (ZaehlerlisteDto zaehlerliste : zaehlerlisten) {
                artikelNachZaehlerlistenId.put(
                        zaehlerliste.getId(),
                        remoteDataSource.ladeArtikel(zaehlerliste.getId())
                );
            }

            if (apiModusAktiv) {
                lokalerInventurSpeicher.loescheAlleDaten();
            }
            lokalerInventurSpeicher.initialisiereZaehlerlisten(zaehlerlisten);
            for (ZaehlerlisteDto zaehlerliste : zaehlerlisten) {
                lokalerInventurSpeicher.initialisiereArtikel(
                        zaehlerliste.getId(),
                        artikelNachZaehlerlistenId.get(zaehlerliste.getId())
                    );
            }
            merkeRemoteErfolg();
            remoteBestandSynchronisiert = !zaehlerlisten.isEmpty();
        } catch (RemoteDatenException exception) {
            merkeRemoteFehlschlag();
            letzteSynchronisierungsFehlermeldung = baueSynchronisierungsFehlermeldung(exception);
            // Ohne Remote-Seed bleibt nur der bereits vorhandene lokale Bestand nutzbar.
        }
    }

    private void synchronisiereAusstehendeAenderungen() {
        synchronisiereAusstehendeAenderungen(false);
    }

    private void synchronisiereAusstehendeAenderungen(boolean ignoriertPause) {
        if (!ignoriertPause && automatischeSynchronisierungPausiert) {
            return;
        }
        if (!darfRemoteVersuchtWerden()) {
            return;
        }

        for (Integer zaehlerlistenId : lokalerInventurSpeicher.ladeZaehlerlistenMitOffenerSynchronisierung()) {
            ZaehlmengenSpeicherAnfrage anfrage = baueSyncAnfrageAusLokalemBestand(zaehlerlistenId);
            if (anfrage == null) {
                continue;
            }

            try {
                ZaehlmengenSpeicherAntwort antwort = remoteDataSource.speichereZaehlmengen(anfrage);
                if (antwort.istErfolgreich()) {
                    Zaehlerliste zaehlerliste = lokalerInventurSpeicher.ladeZaehlerlisteFuerId(zaehlerlistenId);
                    if (zaehlerliste != null && zaehlerliste.istAbgeschlossen()) {
                        remoteDataSource.markiereZaehlerlisteAlsAbgeschlossen(zaehlerlistenId);
                    }
                    merkeRemoteErfolg();
                    lokalerInventurSpeicher.entferneZaehlerlisteAusSynchronisierung(zaehlerlistenId);
                    if (!lokalerInventurSpeicher.hatOffeneSynchronisierung()) {
                        letzteSynchronisierungsFehlermeldung = "";
                    }
                }
            } catch (RemoteDatenException exception) {
                merkeRemoteFehlschlag();
                letzteSynchronisierungsFehlermeldung = baueSynchronisierungsFehlermeldung(exception);
                break;
            }
        }
    }

    private boolean istSpeicherAnfrageGueltig(int zaehlerlistenId, List<Artikel> artikelListe) {
        return zaehlerlistenId > 0 && artikelListe != null && !artikelListe.isEmpty();
    }

    private ZaehlmengenSpeicherAntwort neueLeereSpeicherAntwort() {
        return new ZaehlmengenSpeicherAntwort(
                false,
                "PR: Speichern nicht moeglich. Die Liste enthaelt aktuell keine Artikel.",
                null
        );
    }

    private ZaehlmengenSpeicherAnfrage baueSyncAnfrageAusLokalemBestand(int zaehlerlistenId) {
        Zaehlerliste zaehlerliste = lokalerInventurSpeicher.ladeZaehlerlisteFuerId(zaehlerlistenId);
        if (zaehlerliste == null) {
            lokalerInventurSpeicher.entferneZaehlerlisteAusSynchronisierung(zaehlerlistenId);
            return null;
        }

        List<Artikel> artikelListe = lokalerInventurSpeicher.ladeArtikel(zaehlerlistenId);
        if (artikelListe == null || artikelListe.isEmpty()) {
            lokalerInventurSpeicher.entferneZaehlerlisteAusSynchronisierung(zaehlerlistenId);
            return null;
        }
        return ZaehlmengenSpeicherMapper.mappe(
                zaehlerlistenId,
                zaehlerliste.getGespeicherterZaehlerName(),
                zaehlerliste.getGespeicherterSchreiberName(),
                artikelListe
        );
    }

    private String baueSynchronisierungsFehlermeldung(RemoteDatenException exception) {
        String detail = sammleFehlerdetail(exception);
        String normalisiert = detail.toLowerCase(Locale.GERMANY);

        if (hatUrsache(exception, "UnknownHostException")) {
            return "Der SQL-Server-Host kann vom Tablet aus nicht aufgeloest werden. Bitte Hostname, DNS oder VPN pruefen.\n\nTechnisches Detail: " + detail;
        }
        if (hatUrsache(exception, "SocketTimeoutException") || hatUrsache(exception, "ConnectException")) {
            return "Der SQL-Server ist vom Tablet aus nicht erreichbar. Bitte WLAN, VPN, Firewall und SQL-Port pruefen.\n\nTechnisches Detail: " + detail;
        }
        if (normalisiert.contains("konfiguration ist unvollstaendig")) {
            return "SQL-Server-Konfiguration ist unvollstaendig. Host, Datenbank oder Zugangsdaten fehlen.";
        }
        if (normalisiert.contains("unknownhost")
                || normalisiert.contains("unknown server host")
                || normalisiert.contains("servername konnte nicht aufgel")) {
            return "Der SQL-Server-Host kann vom Tablet aus nicht aufgeloest werden. Bitte Hostname, DNS oder VPN pruefen.\n\nTechnisches Detail: " + detail;
        }
        if (normalisiert.contains("login failed")) {
            return "Die Anmeldung am SQL-Server ist fehlgeschlagen. Benutzername oder Passwort werden vom Server nicht akzeptiert.\n\nTechnisches Detail: " + detail;
        }
        if (normalisiert.contains("cannot open database")) {
            return "Die konfigurierte Datenbank kann nicht geoeffnet werden. Name oder Berechtigung auf dem SQL-Server pruefen.\n\nTechnisches Detail: " + detail;
        }
        if (normalisiert.contains("keine passende bestandszaehlungen-tabelle")) {
            return "Auf dem SQL-Server wurde keine passende Tabelle fuer Bestandszaehlungen gefunden.\n\nTechnisches Detail: " + detail;
        }
        if (normalisiert.contains("invalid object name")) {
            return "Mindestens eine erwartete SQL-Tabelle fehlt oder liegt in einem anderen Schema.\n\nTechnisches Detail: " + detail;
        }
        if (normalisiert.contains("failed to connect")
                || normalisiert.contains("connection refused")
                || normalisiert.contains("connect timed out")
                || normalisiert.contains("timed out")
                || normalisiert.contains("network is unreachable")
                || normalisiert.contains("sockettimeout")
                || normalisiert.contains("connection reset")) {
            return "Der SQL-Server ist vom Tablet aus nicht erreichbar. Bitte WLAN, VPN, Firewall und SQL-Port pruefen.\n\nTechnisches Detail: " + detail;
        }
        return detail;
    }

    private String sammleFehlerdetail(Throwable throwable) {
        String detail = "";
        Throwable aktuell = throwable;
        while (aktuell != null) {
            String meldung = aktuell.getMessage();
            if (meldung != null && !meldung.trim().isEmpty()) {
                detail = meldung.trim();
            }
            aktuell = aktuell.getCause();
        }
        return detail.isEmpty() ? "Unbekannter Fehler bei der Datenbank-Synchronisierung." : detail;
    }

    private boolean hatUrsache(Throwable throwable, String einfacherKlassenname) {
        Throwable aktuell = throwable;
        while (aktuell != null) {
            if (aktuell.getClass().getSimpleName().equals(einfacherKlassenname)) {
                return true;
            }
            aktuell = aktuell.getCause();
        }
        return false;
    }

    private boolean darfRemoteVersuchtWerden() {
        return System.currentTimeMillis() >= naechsterRemoteVersuchAbMillis;
    }

    private void merkeRemoteFehlschlag() {
        naechsterRemoteVersuchAbMillis = System.currentTimeMillis() + REMOTE_FEHLER_COOLDOWN_MS;
    }

    private void merkeRemoteErfolg() {
        naechsterRemoteVersuchAbMillis = 0L;
    }
}
