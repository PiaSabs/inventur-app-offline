package de.pia.inventur.backend.service.sync;

import java.util.UUID;

/** Vertrauensgrenze: nur intern nach Identitaetspruefung erzeugen, niemals aus einem Client-Payload. */
public record GepruefterUrheber(UUID benutzerId) {
    public GepruefterUrheber {
        if (benutzerId == null) throw new IllegalArgumentException("benutzerId ist erforderlich");
    }
}
