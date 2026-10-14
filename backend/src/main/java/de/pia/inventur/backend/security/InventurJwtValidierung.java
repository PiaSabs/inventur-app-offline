package de.pia.inventur.backend.security;

import java.time.Duration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

/** Gemeinsam fuer den echten Decoder und isolierte kryptografische Tests. */
public final class InventurJwtValidierung {
    private InventurJwtValidierung() {}

    public static OAuth2TokenValidator<Jwt> fuer(String issuer, String audience) {
        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("Vertrauenswuerdiger Issuer und Audience erforderlich");
        }
        OAuth2TokenValidator<Jwt> pflichtwerte = jwt -> {
            boolean gueltig = jwt.getExpiresAt() != null && jwt.getAudience() != null
                    && jwt.getAudience().contains(audience);
            Object typ = jwt.getHeaders().get("typ");
            gueltig &= "JWT".equals(typ) || "at+jwt".equals(typ);
            try { new GepruefteOidcIdentitaet(jwt.getClaimAsString("iss"), jwt.getSubject()); }
            catch (IllegalArgumentException invalid) { gueltig = false; }
            return gueltig ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Erforderliche Tokenclaims fehlen oder sind ungueltig", null));
        };
        return new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ofSeconds(30)),
                new JwtIssuerValidator(issuer), pflichtwerte);
    }
}
