package com.example.myapplication;

import java.util.List;

/** Gesperrter Platzhalter: keine JDBC-Verbindung und noch keine REST-Integration. */
public final class ApiInventurRemoteDataSource implements InventurRemoteDataSource {
    public ApiInventurRemoteDataSource(LokalerInventurSpeicher speicher) { }
    private RemoteDatenException nichtVerfuegbar() {
        return new RemoteDatenException("REST-Anbindung noch nicht implementiert. Lokale Demo verwenden.");
    }
    @Override public void pruefeVerbindung() throws RemoteDatenException { throw nichtVerfuegbar(); }
    @Override public List<ZaehlerlisteDto> ladeZaehlerlisten() throws RemoteDatenException { throw nichtVerfuegbar(); }
    @Override public List<ArtikelDto> ladeArtikel(int id) throws RemoteDatenException { throw nichtVerfuegbar(); }
    @Override public ZaehlmengenSpeicherAntwort speichereZaehlmengen(ZaehlmengenSpeicherAnfrage anfrage)
            throws RemoteDatenException { throw nichtVerfuegbar(); }
    @Override public void markiereZaehlerlisteAlsAbgeschlossen(int id) throws RemoteDatenException { throw nichtVerfuegbar(); }
    @Override public String holeLetzteDiagnose() { return "Lokale Demo; kein externer Datenbankzugriff."; }
}
