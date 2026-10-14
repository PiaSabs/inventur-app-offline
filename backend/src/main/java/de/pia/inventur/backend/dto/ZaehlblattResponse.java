package de.pia.inventur.backend.dto;

import java.time.Instant;
import java.util.UUID;

public record ZaehlblattResponse(UUID id, UUID sitzungId, String blattnummer,
        String status, long statusVersion, Instant abgeschlossenAm) {
}
