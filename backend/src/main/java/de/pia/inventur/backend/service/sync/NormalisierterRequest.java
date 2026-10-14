package de.pia.inventur.backend.service.sync;

/** Die drei Werte passen zu den vorhandenen Inhaltsfeldern in sync_request. */
public record NormalisierterRequest(int inhaltFormatVersion, String normalisierterInhalt,
        String inhaltFingerprint) {
}
