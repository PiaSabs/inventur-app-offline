package de.pia.inventur.backend;

import java.time.Instant;
import java.util.Date;
import java.util.Map;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.pia.inventur.backend.security.InventurJwtValidierung;
import de.pia.inventur.backend.security.StrikterJwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** RSA-Schluessel ausschliesslich im Test-Arbeitsspeicher, nie in Dateien oder Logs. */
final class JwtTestSchluessel {
    static final String ISSUER = "https://login.jwt-demo.example/inventur";
    static final String AUDIENCE = "inventur-demo-api";
    static final String SUBJECT = "demo-aktiver-zaehler";
    private final RSAKey rsa;

    JwtTestSchluessel() {
        try { rsa = new RSAKeyGenerator(2048).keyID("demo-key").generate(); }
        catch (Exception error) { throw new IllegalStateException("Testschluessel konnte nicht erzeugt werden", error); }
    }

    JwtDecoder decoder() {
        try {
            var decoder = NimbusJwtDecoder.withPublicKey(rsa.toRSAPublicKey())
                    .signatureAlgorithm(SignatureAlgorithm.RS256).validateType(false).build();
            decoder.setJwtValidator(InventurJwtValidierung.fuer(ISSUER, AUDIENCE));
            return new StrikterJwtDecoder(decoder);
        } catch (Exception error) { throw new IllegalStateException("Testdecoder konnte nicht erzeugt werden", error); }
    }

    String token() { return token(Map.of()); }

    String token(Map<String, Object> aenderungen) { return token(aenderungen, JWSAlgorithm.RS256, "JWT"); }

    String token(Map<String, Object> aenderungen, JWSAlgorithm algorithmus, String typ) {
        try {
            var now = Instant.now();
            var claims = new JWTClaimsSet.Builder().issuer(ISSUER).subject(SUBJECT).audience(AUDIENCE)
                    .issueTime(Date.from(now.minusSeconds(60))).notBeforeTime(Date.from(now.minusSeconds(60)))
                    .expirationTime(Date.from(now.plusSeconds(300)));
            aenderungen.forEach(claims::claim);
            var header = new JWSHeader.Builder(algorithmus).keyID("demo-key")
                    .type(typ == null ? null : new JOSEObjectType(typ)).build();
            var token = new SignedJWT(header, claims.build());
            token.sign(new RSASSASigner(rsa));
            return token.serialize();
        } catch (Exception error) { throw new IllegalStateException("Testsignatur konnte nicht erzeugt werden", error); }
    }
}
