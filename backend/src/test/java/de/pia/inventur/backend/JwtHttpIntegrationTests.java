package de.pia.inventur.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import de.pia.inventur.backend.security.InventurPrincipal;
import de.pia.inventur.backend.service.sync.GepruefterUrheber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(JwtHttpIntegrationTests.TestDecoder.class)
@Sql(scripts = "/test-demo.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Sql(statements = {
        "UPDATE benutzer SET oidc_issuer='https://login.jwt-demo.example/inventur', oidc_subject='demo-aktiver-zaehler' WHERE id='50000000-0000-4000-8000-000000000001'",
        "UPDATE benutzer SET oidc_issuer='https://login.jwt-demo.example/inventur', oidc_subject='demo-inaktiv', aktiv=false WHERE id='50000000-0000-4000-8000-000000000002'"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class JwtHttpIntegrationTests {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("inventur_test").withUsername("inventur_test").withPassword(UUID.randomUUID().toString());
    private static final JwtTestSchluessel keys = new JwtTestSchluessel();
    @Autowired private MockMvc mvc;
    @Autowired private AuthenticationManager authenticationManager;
    @Autowired private JdbcTemplate jdbc;
    @LocalServerPort private int port;

    @TestConfiguration(proxyBeanMethods = false)
    static class TestDecoder {
        @Bean @Primary JwtDecoder isolierterTestDecoder() { return keys.decoder(); }
    }

    @Test
    void verifiedTokenAndActiveDatabaseMappingProduceLocalTrustedPrincipalWithoutTokenCredentials() {
        assertEquals("inventur_test", jdbc.queryForObject("SELECT current_database()", String.class));
        var authentication = authenticationManager.authenticate(new BearerTokenAuthenticationToken(keys.token()));
        assertTrue(authentication.isAuthenticated());
        var principal = assertInstanceOf(InventurPrincipal.class, authentication.getPrincipal());
        UUID localId = UUID.fromString("50000000-0000-4000-8000-000000000001");
        assertEquals(localId, principal.benutzerId()); assertEquals(localId.toString(), authentication.getName());
        assertNull(authentication.getCredentials()); assertTrue(authentication.getAuthorities().isEmpty());
        // Erst hier sind Signatur, Claims und aktive DB-Zuordnung tatsaechlich geprueft.
        assertEquals(localId, new GepruefterUrheber(principal.benutzerId()).benutzerId());
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM benutzer", Integer.class));
    }

    @Test
    void tokenRoleAndLocalUserClaimsAreNeverAdoptedAsInventurPermissions() {
        var token = keys.token(Map.of("benutzerId", UUID.randomUUID().toString(), "roles", java.util.List.of("ADMIN")));
        var auth = authenticationManager.authenticate(new BearerTokenAuthenticationToken(token));
        assertEquals(UUID.fromString("50000000-0000-4000-8000-000000000001"),
                assertInstanceOf(InventurPrincipal.class, auth.getPrincipal()).benutzerId());
        assertTrue(auth.getAuthorities().isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"demo-unbekannt", "demo-inaktiv"})
    void unknownAndInactiveSubjectsCannotBecomeAuthenticatedUsers(String subject) throws Exception {
        String token = keys.token(Map.of("sub", subject));
        assertThrows(AuthenticationException.class, () -> authenticationManager.authenticate(new BearerTokenAuthenticationToken(token)));
        mvc.perform(get("/api/v1/zaehlerlisten").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("AUTHENTIFIZIERUNG_ERFORDERLICH"));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM benutzer", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/zaehlerlisten",
            "/api/v1/zaehlerlisten/10000000-0000-4000-8000-000000000001/sitzungen",
            "/api/v1/sitzungen/20000000-0000-4000-8000-000000000001/blaetter",
            "/api/v1/sitzungen/20000000-0000-4000-8000-000000000001/blaetter/30000000-0000-4000-8000-000000000001",
            "/api/v1/unbekannt", "/debug"})
    void authenticatedReadsAreScopedAndUnknownRoutesRemainClosed(String path) throws Exception {
        int expected = path.equals("/api/v1/zaehlerlisten") || path.contains("/zaehlerlisten/10000000")
                ? 200 : path.equals("/debug") || path.equals("/api/v1/unbekannt") ? 403 : 404;
        mvc.perform(get(path).header("Authorization", "Bearer " + keys.token()))
                .andExpect(status().is(expected));
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @Test
    void queryAndFormTokensCannotAuthenticateAndWritesStayClosed() throws Exception {
        mvc.perform(get("/api/v1/zaehlerlisten").param("access_token", keys.token())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/zaehlerlisten").contentType("application/x-www-form-urlencoded")
                .param("access_token", keys.token())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/zaehlerlisten").header("Authorization", "Bearer " + keys.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidCryptoOrClaimsReturnGeneric401OverHttp() throws Exception {
        for (String token : new String[] {new JwtTestSchluessel().token(), keys.token(Map.of("aud", "wrong-demo-api")),
                keys.token(Map.of("iss", "https://wrong.jwt-demo.example"))}) {
            mvc.perform(get("/api/v1/zaehlerlisten").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("status").value(401))
                    .andExpect(jsonPath("detail").value("Eine gueltige Anmeldung ist erforderlich."));
        }
    }

    @Test
    void realHttpServerKeepsHealthPublicAndAllowsAuthorizedLists() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var health = client.send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/v1/health"))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode()); assertEquals("{\"status\":\"UP\"}", health.body());
            var lists = client.send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/v1/zaehlerlisten"))
                    .header("Authorization", "Bearer " + keys.token()).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, lists.statusCode());
            assertEquals(2, tools.jackson.databind.json.JsonMapper.builder().build().readTree(lists.body()).size());
        }
    }
}
