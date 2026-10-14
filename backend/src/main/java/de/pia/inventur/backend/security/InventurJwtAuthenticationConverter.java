package de.pia.inventur.backend.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;

/** Ausschliesslich als Converter des JwtAuthenticationProvider nach kryptografischer Pruefung verwenden. */
public class InventurJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final OidcBenutzerZuordnung zuordnung;

    public InventurJwtAuthenticationConverter(OidcBenutzerZuordnung zuordnung) { this.zuordnung = zuordnung; }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        var identitaet = new GepruefteOidcIdentitaet(jwt.getClaimAsString("iss"), jwt.getSubject());
        var id = zuordnung.findeAktivenBenutzer(identitaet).orElseThrow(() ->
                new OAuth2AuthenticationException(new OAuth2Error("invalid_token"), "Keine aktive Inventuridentitaet"));
        return new InventurAuthentifizierung(new InventurPrincipal(id));
    }
}
