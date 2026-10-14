package de.pia.inventur.backend.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/** Wird intern erst nach Decoderpruefung und erfolgreicher aktiver Zuordnung erzeugt. */
public final class InventurAuthentifizierung extends AbstractAuthenticationToken {
    private final InventurPrincipal principal;

    InventurAuthentifizierung(InventurPrincipal principal) {
        super(List.of());
        this.principal = principal;
        super.setAuthenticated(true);
    }

    @Override public InventurPrincipal getPrincipal() { return principal; }
    @Override public Object getCredentials() { return null; }
    @Override public void setAuthenticated(boolean authenticated) {
        if (authenticated) throw new IllegalArgumentException("Authentifizierung nur nach interner Pruefung");
        super.setAuthenticated(false);
    }
}
