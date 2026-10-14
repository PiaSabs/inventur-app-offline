package de.pia.inventur.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import de.pia.inventur.backend.controller.HealthController;
import de.pia.inventur.backend.security.SecurityFehlerHandler;
import de.pia.inventur.backend.security.SecurityKonfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import de.pia.inventur.backend.security.OidcBenutzerZuordnung;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(HealthController.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(SecurityKonfiguration.class)
class HttpSecurityTests {
    @Autowired private MockMvc mvc;
    @Autowired private ApplicationContext context;
    @Autowired private SecurityFehlerHandler fehler;
    @Autowired private AuthenticationManager authenticationManager;
    @MockitoBean private OidcBenutzerZuordnung oidcBenutzerZuordnung;

    @Test
    void anonymousHealthStaysPublicAndUnchangedWithoutCreatingSession() throws Exception {
        var result = mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk()).andExpect(content().json("{\"status\":\"UP\"}"))
                .andReturn();
        assertNull(result.getRequest().getSession(false));
        assertEquals(0, result.getResponse().getCookies().length);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/zaehlerlisten",
            "/api/v1/zaehlerlisten/10000000-0000-4000-8000-000000000001/sitzungen",
            "/api/v1/sitzungen/20000000-0000-4000-8000-000000000001/blaetter",
            "/api/v1/sitzungen/20000000-0000-4000-8000-000000000001/blaetter/30000000-0000-4000-8000-000000000001",
            "/api/v1/unbekannt", "/login", "/logout", "/debug", "/actuator", "/actuator/env", "/error"})
    void allOtherRoutesReturnSameJson401WithoutRedirectOrSession(String path) throws Exception {
        var result = mvc.perform(get(path)).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"status":401,"code":"AUTHENTIFIZIERUNG_ERFORDERLICH",
                         "detail":"Eine gueltige Anmeldung ist erforderlich."}
                        """))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(header().string("WWW-Authenticate", "Bearer")).andReturn();
        assertEquals(3, JsonMapper.builder().build().readTree(result.getResponse().getContentAsString()).size());
        assertNull(result.getRequest().getSession(false));
        assertEquals(0, result.getResponse().getCookies().length);
    }

    @Test
    void onlyGetHealthIsPublicAndWritesWithoutLoginReturn401InsteadOfCsrf403() throws Exception {
        mvc.perform(post("/api/v1/health")).andExpect(status().isUnauthorized());
        mvc.perform(head("/api/v1/health")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/zaehlerlisten")).andExpect(status().isUnauthorized());
    }

    @Test
    void arbitraryBearerBasicAndClientIdentityHeadersCannotAuthenticate() throws Exception {
        for (String authorization : new String[] {"Bearer ungueltiger-testwert", "Basic ungueltiger-testwert"}) {
            mvc.perform(get("/api/v1/zaehlerlisten").header("Authorization", authorization)
                    .header("X-Benutzer-Id", "50000000-0000-4000-8000-000000000001"))
                    .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
        }
    }

    @Test
    void previousSessionIdentityIsNotUsedByStatelessApi() throws Exception {
        var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("fachtest", null, java.util.List.of()));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);
        mvc.perform(get("/api/v1/zaehlerlisten").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void applicationHasNoGeneratedUserAndRejectsAuthenticationAttempts() {
        assertTrue(context.getBeansOfType(UserDetailsService.class).isEmpty());
        assertThrows(AuthenticationException.class, () -> authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("fachtest", null)));
    }

    @Test
    void realSignedTestTokenCannotAuthenticateWithoutTrustedProductionConfiguration() throws Exception {
        var keys = new JwtTestSchluessel();
        mvc.perform(get("/api/v1/zaehlerlisten").header("Authorization", "Bearer " + keys.token()))
                .andExpect(status().isUnauthorized());
        org.mockito.Mockito.verifyNoInteractions(oidcBenutzerZuordnung);
    }

    @Test
    void prepared403HandlerReturnsGenericJsonWithoutExposingInternalCause() throws Exception {
        var response = new MockHttpServletResponse();
        fehler.handle(new MockHttpServletRequest(), response, new AccessDeniedException("Vertrauliche interne Ursache"));
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"));
        assertEquals(JsonMapper.builder().build().readTree("""
                {"status":403,"code":"ZUGRIFF_VERWEIGERT","detail":"Die erforderliche Berechtigung fehlt."}
                """), JsonMapper.builder().build().readTree(response.getContentAsString()));
    }
}
