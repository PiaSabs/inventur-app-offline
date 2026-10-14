package de.pia.inventur.backend.security;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import de.pia.inventur.backend.entity.BenutzerEntity;
import de.pia.inventur.backend.repository.BenutzerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Interne Zuordnung nach zukuenftiger Tokenpruefung, kein Anmeldeweg oder Benutzer-Provisioning. */
@Service
public class OidcBenutzerZuordnung {
    private final BenutzerRepository benutzer;

    public OidcBenutzerZuordnung(BenutzerRepository benutzer) { this.benutzer = benutzer; }

    /** Leer bedeutet abgelehnt, ohne zwischen unbekanntem und deaktiviertem Konto zu unterscheiden. */
    @Transactional(readOnly = true)
    public Optional<UUID> findeAktivenBenutzer(GepruefteOidcIdentitaet identitaet) {
        Objects.requireNonNull(identitaet, "Gepruefte Identitaet erforderlich");
        return benutzer.findByOidcIssuerAndOidcSubject(identitaet.issuer(), identitaet.subject())
                .filter(BenutzerEntity::isAktiv)
                .map(BenutzerEntity::getId);
    }
}
