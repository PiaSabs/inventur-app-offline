package de.pia.inventur.backend.dto;

import java.util.UUID;

public record ZaehlpositionResponse(UUID id, int positionsnummer, String lagerort,
        Integer istMenge, Integer mindestbestand, ArtikelResponse artikel,
        ZaehlergebnisResponse ergebnis) {
}
