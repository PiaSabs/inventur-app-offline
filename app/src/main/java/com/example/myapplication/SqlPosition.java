package com.example.myapplication;

// Unveraenderter Positionsschluessel ohne Mengen oder Durchgangsannahmen.
public final class SqlPosition {
    final Long id;
    final Integer blatt;
    final Integer position;
    final String artikelnummer;

    public SqlPosition(Long id, Integer blatt, Integer position, String artikelnummer) {
        this.id = id;
        this.blatt = blatt;
        this.position = position;
        this.artikelnummer = artikelnummer;
    }
}
