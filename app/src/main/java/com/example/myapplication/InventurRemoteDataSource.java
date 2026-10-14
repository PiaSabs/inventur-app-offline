package com.example.myapplication;

import java.util.List;

public interface InventurRemoteDataSource {

    void pruefeVerbindung() throws RemoteDatenException;

    List<ZaehlerlisteDto> ladeZaehlerlisten() throws RemoteDatenException;

    List<ArtikelDto> ladeArtikel(int zaehlerlistenId) throws RemoteDatenException;

    ZaehlmengenSpeicherAntwort speichereZaehlmengen(ZaehlmengenSpeicherAnfrage anfrage) throws RemoteDatenException;

    void markiereZaehlerlisteAlsAbgeschlossen(int zaehlerlistenId) throws RemoteDatenException;

    String holeLetzteDiagnose();
}
