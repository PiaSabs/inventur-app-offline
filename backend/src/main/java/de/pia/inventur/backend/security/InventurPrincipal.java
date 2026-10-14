package de.pia.inventur.backend.security;

import java.security.Principal;
import java.util.Objects;
import java.util.UUID;

/** Lokale UUID aus der Datenbank; keine externen Benutzerclaims oder Tokenwerte. */
public record InventurPrincipal(UUID benutzerId) implements Principal {
    public InventurPrincipal { Objects.requireNonNull(benutzerId); }
    @Override public String getName() { return benutzerId.toString(); }
}
