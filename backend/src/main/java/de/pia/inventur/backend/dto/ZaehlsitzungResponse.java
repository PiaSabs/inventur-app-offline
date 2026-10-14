package de.pia.inventur.backend.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ZaehlsitzungResponse(UUID id, UUID zaehlerlisteId, LocalDate zaehltag,
        int rundeNr, String status, long statusVersion, Instant gestartetAm,
        Instant abgeschlossenAm) {
}
