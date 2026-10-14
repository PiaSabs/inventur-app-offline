package de.pia.inventur.backend.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import de.pia.inventur.backend.dto.SecurityFehlerAntwort;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

public class SecurityFehlerHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        // Keine internen Tokenfehler oder Claims im Challenge-Header offenlegen.
        response.setHeader("WWW-Authenticate", "Bearer");
        schreibe(response, new SecurityFehlerAntwort(401, "AUTHENTIFIZIERUNG_ERFORDERLICH",
                "Eine gueltige Anmeldung ist erforderlich."));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        schreibe(response, new SecurityFehlerAntwort(403, "ZUGRIFF_VERWEIGERT",
                "Die erforderliche Berechtigung fehlt."));
    }

    private void schreibe(HttpServletResponse response, SecurityFehlerAntwort fehler) throws IOException {
        response.setStatus(fehler.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(mapper.writeValueAsString(fehler));
    }
}
