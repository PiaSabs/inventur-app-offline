package de.pia.inventur.backend.dto;

import java.util.UUID;

public record ArtikelResponse(UUID id, String artikelnummer, String bezeichnung) {
}
