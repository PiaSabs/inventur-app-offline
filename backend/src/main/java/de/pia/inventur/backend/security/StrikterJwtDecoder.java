package de.pia.inventur.backend.security;

import java.util.List;
import java.util.Map;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Nimbus prueft die Kryptografie; danach werden Originaltypen vor Claim-Coercion kontrolliert. */
public final class StrikterJwtDecoder implements JwtDecoder {
    private final JwtDecoder delegate;

    public StrikterJwtDecoder(JwtDecoder delegate) { this.delegate = delegate; }

    @Override
    public Jwt decode(String token) {
        Jwt jwt = delegate.decode(token);
        Map<String, Object> raw;
        try { raw = SignedJWT.parse(token).getPayload().toJSONObject(); }
        catch (Exception invalid) { throw new BadJwtException("Signierter Tokeninhalt ist ungueltig"); }
        if (raw == null || !(raw.get("iss") instanceof String issuer) || !(raw.get("sub") instanceof String subject)
                || !issuer.equals(jwt.getClaimAsString("iss")) || !subject.equals(jwt.getSubject())
                || ungueltigerText(issuer) || ungueltigerText(subject)
                || !(raw.get("exp") instanceof Number)
                || (raw.containsKey("nbf") && !(raw.get("nbf") instanceof Number))
                || (raw.containsKey("iat") && !(raw.get("iat") instanceof Number))) {
            throw new BadJwtException("Erforderliche Claimtypen sind ungueltig");
        }
        Object audience = raw.get("aud");
        if (!(audience instanceof String) && !(audience instanceof List<?> liste
                && liste.stream().allMatch(String.class::isInstance))) {
            throw new BadJwtException("Audienceclaim ist ungueltig");
        }
        return jwt;
    }

    private boolean ungueltigerText(String wert) {
        return wert.codePoints().anyMatch(zeichen -> zeichen == 0 || (zeichen >= 0xD800 && zeichen <= 0xDFFF));
    }
}
