package de.pia.inventur.backend.security;

import java.util.UUID;
import de.pia.inventur.backend.entity.BenutzerRolle;
import de.pia.inventur.backend.repository.BenutzerRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Normale Zaehler-Lesezugriffe; keine globale Rollenregel fuer Sync-Replays oder Admin-Funktionen. */
@Service
public class LeseBerechtigung {
    private final BenutzerRepository benutzer;

    public LeseBerechtigung(BenutzerRepository benutzer) { this.benutzer = benutzer; }

    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public UUID aktuellerZaehler() {
        var anmeldung = SecurityContextHolder.getContext().getAuthentication();
        if (anmeldung == null || !anmeldung.isAuthenticated()
                || !(anmeldung.getPrincipal() instanceof InventurPrincipal principal)) {
            throw new AuthenticationCredentialsNotFoundException("Gueltige Inventuridentitaet erforderlich");
        }
        var status = benutzer.findeLeseStatus(principal.benutzerId()).orElseThrow(() ->
                new AuthenticationCredentialsNotFoundException("Gueltige Inventuridentitaet erforderlich"));
        if (!status.getAktiv()) throw new AuthenticationCredentialsNotFoundException("Gueltige Inventuridentitaet erforderlich");
        if (status.getRolle() != BenutzerRolle.ZAEHLER) throw new AccessDeniedException("Zaehlerrolle erforderlich");
        return principal.benutzerId();
    }
}
