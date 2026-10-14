package de.pia.inventur.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import de.pia.inventur.backend.security.JwtDecoderKonfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.oauth2.jwt.JwtException;

class JwtValidierungTests {
    private static final JwtTestSchluessel keys = new JwtTestSchluessel();

    @Test
    void realRs256SignatureAndRequiredClaimsAreAccepted() {
        var decoded = keys.decoder().decode(keys.token());
        assertEquals(JwtTestSchluessel.SUBJECT, decoded.getSubject());
        assertTrue(decoded.getAudience().contains(JwtTestSchluessel.AUDIENCE));
    }

    @Test
    void validAccessTokenTypeAndAudienceListAreAccepted() {
        var decoded = keys.decoder().decode(keys.token(Map.of("aud", java.util.List.of("other-demo-api", JwtTestSchluessel.AUDIENCE)), JWSAlgorithm.RS256, "at+jwt"));
        assertEquals(JwtTestSchluessel.SUBJECT, decoded.getSubject());
    }

    @Test
    void manipulatedSignatureOrUntrustedSigningKeyIsRejected() {
        String signed = keys.token();
        var parts = signed.split("\\.");
        parts[2] = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
        assertThrows(JwtException.class, () -> keys.decoder().decode(String.join(".", parts)));
        var untrusted = new JwtTestSchluessel();
        assertThrows(JwtException.class, () -> keys.decoder().decode(untrusted.token()));
    }

    @Test
    void unsignedJwtAndOtherSignatureAlgorithmsAreRejected() {
        String unsigned = new PlainJWT(new JWTClaimsSet.Builder().subject("demo").build()).serialize();
        assertThrows(JwtException.class, () -> keys.decoder().decode(unsigned));
        assertThrows(JwtException.class, () -> keys.decoder().decode(keys.token(Map.of(), JWSAlgorithm.RS512, "JWT")));
    }

    static Stream<Arguments> invalidClaims() {
        return Stream.of(Arguments.of("iss", "https://wrong.jwt-demo.example/inventur"),
                Arguments.of("iss", JwtTestSchluessel.ISSUER + "/"), Arguments.of("iss", null),
                Arguments.of("aud", "other-demo-api"), Arguments.of("aud", null),
                Arguments.of("exp", Date.from(Instant.now().minusSeconds(120))), Arguments.of("exp", null),
                Arguments.of("nbf", Date.from(Instant.now().plusSeconds(120))),
                Arguments.of("sub", null), Arguments.of("sub", ""), Arguments.of("sub", "\t\n\u00a0"),
                Arguments.of("sub", 42), Arguments.of("exp", "ungueltig"));
    }

    @ParameterizedTest @MethodSource("invalidClaims")
    void missingWrongOrTemporallyInvalidClaimsAreRejected(String claim, Object value) {
        var changes = new HashMap<String, Object>(); changes.put(claim, value);
        assertThrows(JwtException.class, () -> keys.decoder().decode(keys.token(changes)));
    }

    @Test
    void unexpectedOrMissingTokenTypeIsRejected() {
        assertThrows(JwtException.class, () -> keys.decoder().decode(keys.token(Map.of(), JWSAlgorithm.RS256, "ID")));
        assertThrows(JwtException.class, () -> keys.decoder().decode(keys.token(Map.of(), JWSAlgorithm.RS256, null)));
    }

    @Test
    void missingConfigurationRejectsEvenCorrectlySignedTokens() {
        var closed = JwtDecoderKonfiguration.erzeugeDecoder(null, null, null);
        assertThrows(JwtException.class, () -> closed.decode(keys.token()));
    }

    static Stream<Arguments> invalidConfiguration() {
        return Stream.of(Arguments.of(JwtTestSchluessel.ISSUER, null, null),
                Arguments.of(null, JwtTestSchluessel.AUDIENCE, "https://keys.jwt-demo.example/jwks"),
                Arguments.of(JwtTestSchluessel.ISSUER, JwtTestSchluessel.AUDIENCE, "http://keys.jwt-demo.example/jwks"),
                Arguments.of("http://login.jwt-demo.example", JwtTestSchluessel.AUDIENCE, "https://keys.jwt-demo.example/jwks"),
                Arguments.of(JwtTestSchluessel.ISSUER, " ", "https://keys.jwt-demo.example/jwks"));
    }

    @ParameterizedTest @MethodSource("invalidConfiguration")
    void incompleteOrUnsafeConfigurationStopsStartup(String issuer, String audience, String jwks) {
        assertThrows(IllegalStateException.class, () -> JwtDecoderKonfiguration.erzeugeDecoder(issuer, audience, jwks));
    }
}
