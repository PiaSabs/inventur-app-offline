package de.pia.inventur.backend.service.sync;

import java.util.Objects;

/** Nur entscheidung != null ist dauerhaft gespeichert; sonst ist code eine aktuelle Ablehnung. */
public record ErgebnisSpeicherergebnis(String code, GespeicherteRequestEntscheidung entscheidung,
        boolean wiederholung) {
    public ErgebnisSpeicherergebnis {
        Objects.requireNonNull(code);
        if (wiederholung && entscheidung == null) throw new IllegalArgumentException("Replay braucht eine gespeicherte Antwort");
    }
}
