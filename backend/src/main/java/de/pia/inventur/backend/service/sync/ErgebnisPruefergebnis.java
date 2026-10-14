package de.pia.inventur.backend.service.sync;

/** Keine fremden IDs, Versionsstaende oder Ergebniswerte in Fehlerdetails. */
public record ErgebnisPruefergebnis(ErgebnisPruefcode code) {
    public ErgebnisPruefergebnis {
        if (code == null) throw new IllegalArgumentException("Pruefcode ist erforderlich");
    }

    public boolean zulaessig() { return code == ErgebnisPruefcode.ZULAESSIG; }
}
