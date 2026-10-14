package de.pia.inventur.backend.dto;

import java.util.UUID;

public record ZaehlerlisteResponse(UUID id, String code, String bezeichnung) {
}
