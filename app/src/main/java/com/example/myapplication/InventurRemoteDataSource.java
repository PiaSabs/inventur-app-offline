package com.example.myapplication;

import java.util.List;

public interface InventurRemoteDataSource {

    default boolean istSchreibgeschuetzt() { return false; }

    void pruefeVerbindung() throws RemoteDatenException;

    List<ZaehlerlisteDto> ladeZaehlerlisten() throws RemoteDatenException;

    default List<ZaehlerlisteDto> ladeListenuebersicht() throws RemoteDatenException {
        return ladeZaehlerlisten();
    }

    List<ArtikelDto> ladeArtikel(int zaehlerlistenId) throws RemoteDatenException;

    ZaehlmengenSpeicherAntwort speichereZaehlmengen(ZaehlmengenSpeicherAnfrage anfrage) throws RemoteDatenException;

    void markiereZaehlerlisteAlsAbgeschlossen(int zaehlerlistenId) throws RemoteDatenException;

    String holeLetzteDiagnose();
}
