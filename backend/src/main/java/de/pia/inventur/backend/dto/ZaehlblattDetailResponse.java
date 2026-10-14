package de.pia.inventur.backend.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ZaehlblattDetailResponse(UUID id, UUID sitzungId, String blattnummer,
        String status, long statusVersion, Instant abgeschlossenAm,
        List<ZaehlpositionResponse> positionen) {
}
