package de.pia.inventur.backend.security;

import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration(proxyBeanMethods = false)
public class JwtDecoderKonfiguration {
    @Bean
    JwtDecoder jwtDecoder(Environment env) {
        String issuer = env.getProperty("inventur.security.jwt.issuer");
        String audience = env.getProperty("inventur.security.jwt.audience");
        String jwks = env.getProperty("inventur.security.jwt.jwk-set-uri");
        return erzeugeDecoder(issuer, audience, jwks);
    }

    // Keine Testschluessel, lokalen Schluesseldateien oder aus dem Token gelesenen Vertrauensanker.
    public static JwtDecoder erzeugeDecoder(String issuer, String audience, String jwks) {
        if (leer(issuer) && leer(audience) && leer(jwks)) {
            return token -> { throw new BadJwtException("Keine vertrauenswuerdige JWT-Konfiguration vorhanden"); };
        }
        if (leer(issuer) || leer(audience) || leer(jwks) || !https(issuer) || !https(jwks)
                || !audience.equals(audience.strip())) {
            throw new IllegalStateException("JWT-Konfiguration muss vollstaendig und vertrauenswuerdig sein");
        }
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).jwsAlgorithm(SignatureAlgorithm.RS256)
                .validateType(false).build();
        decoder.setJwtValidator(InventurJwtValidierung.fuer(issuer, audience));
        return new StrikterJwtDecoder(decoder);
    }

    private static boolean leer(String wert) { return wert == null || wert.isBlank(); }
    private static boolean https(String wert) {
        try {
            var uri = URI.create(wert);
            return "https".equals(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException invalid) { return false; }
    }
}
