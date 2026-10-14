package de.pia.inventur.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import de.pia.inventur.backend.entity.BenutzerEntity;
import de.pia.inventur.backend.entity.BenutzerRolle;
import de.pia.inventur.backend.security.InventurPrincipal;
import de.pia.inventur.backend.service.ZaehlerlistenService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Import(LeseAutorisierungIntegrationTests.TestDecoder.class)
@Sql(scripts = {"/test-demo.sql", "/test-blatt-sitzungen.sql", "/test-positionen.sql", "/test-ergebnisse.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Sql(statements = {
        "INSERT INTO benutzer(id, anzeigename, rolle, aktiv) VALUES ('50000000-0000-4000-8000-000000000004', 'Demo-Zaehler ohne Listen', 'ZAEHLER', true)",
        "UPDATE benutzer SET oidc_issuer='https://login.jwt-demo.example/inventur', oidc_subject='demo-aktiver-zaehler' WHERE id='50000000-0000-4000-8000-000000000001'",
        "UPDATE benutzer SET oidc_issuer='https://login.jwt-demo.example/inventur', oidc_subject='demo-zaehler-b' WHERE id='50000000-0000-4000-8000-000000000002'",
        "UPDATE benutzer SET oidc_issuer='https://login.jwt-demo.example/inventur', oidc_subject='demo-admin' WHERE id='50000000-0000-4000-8000-000000000003'",
        "UPDATE benutzer SET oidc_issuer='https://login.jwt-demo.example/inventur', oidc_subject='demo-ohne-listen' WHERE id='50000000-0000-4000-8000-000000000004'",
        "INSERT INTO zaehlsitzung(id, zaehlerliste_id, zaehltag, runde_nr, status, status_version, gestartet_am) VALUES ('71000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000002', '2026-10-14', 1, 'OFFEN', 1, '2026-10-14T08:00:00Z')",
        "INSERT INTO zaehlblatt(id, zaehlsitzung_id, blattnummer, status, status_version) VALUES ('81000000-0000-4000-8000-000000000001', '71000000-0000-4000-8000-000000000001', '1', 'OFFEN', 1)",
        "INSERT INTO zaehlposition(id, zaehlblatt_id, artikel_id, positionsnummer) VALUES ('86000000-0000-4000-8000-000000000001', '81000000-0000-4000-8000-000000000001', '83000000-0000-4000-8000-000000000001', 1)",
        "INSERT INTO zaehlergebnis(id, zaehlposition_id, zaehlmenge, anmerkung, revision) VALUES ('87000000-0000-4000-8000-000000000001', '86000000-0000-4000-8000-000000000001', 99, 'Demo-Fremdergebnis', 1)"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class LeseAutorisierungIntegrationTests {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("inventur_test").withUsername("inventur_test").withPassword(UUID.randomUUID().toString());
    private static final JwtTestSchluessel keys = new JwtTestSchluessel();
    private static final String LIST_A = "10000000-0000-4000-8000-000000000001";
    private static final String LIST_B = "10000000-0000-4000-8000-000000000002";
    private static final String SESSION_A = "70000000-0000-4000-8000-000000000001";
    private static final String SESSION_A2 = "70000000-0000-4000-8000-000000000002";
    private static final String SESSION_B = "71000000-0000-4000-8000-000000000001";
    private static final String SHEET_A = "82000000-0000-4000-8000-000000000001";
    private static final String SHEET_B = "81000000-0000-4000-8000-000000000001";
    private static final String UNKNOWN = "90000000-0000-4000-8000-000000000001";
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory emf;
    @Autowired private ZaehlerlistenService listenService;
    @PersistenceContext private EntityManager em;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @TestConfiguration(proxyBeanMethods = false)
    static class TestDecoder {
        @Bean @Primary JwtDecoder isolierterTestDecoder() { return keys.decoder(); }
    }

    @BeforeEach
    void aktuelleTestzuweisungen() {
        jdbc.update("UPDATE benutzer SET aktiv=true, rolle='ZAEHLER' WHERE id IN ('50000000-0000-4000-8000-000000000001','50000000-0000-4000-8000-000000000002')");
        jdbc.update("UPDATE zaehlerliste SET aktueller_zaehler_id='50000000-0000-4000-8000-000000000001', zuweisung_version=1 WHERE id=?::uuid", LIST_A);
        jdbc.update("UPDATE zaehlerliste SET aktueller_zaehler_id='50000000-0000-4000-8000-000000000002', zuweisung_version=1 WHERE id=?::uuid", LIST_B);
    }

    @Test
    void healthRemainsPublicAndListsAreFilteredInDatabaseWithUnchangedJson() throws Exception {
        assertEquals("inventur_test", jdbc.queryForObject("SELECT current_database()", String.class));
        var health = get("/api/v1/health", null); assertEquals(200, health.statusCode()); assertEquals("{\"status\":\"UP\"}", health.body());
        var lists = get("/api/v1/zaehlerlisten", keys.token()); assertEquals(200, lists.statusCode());
        assertEquals(mapper.readTree("""
                [{"id":"10000000-0000-4000-8000-000000000001","code":"DEMO-01","bezeichnung":"Demo-Zaehlerliste Sonnenlager"}]
                """), mapper.readTree(lists.body()));
        var other = get("/api/v1/zaehlerlisten", token("demo-zaehler-b")); assertEquals(200, other.statusCode());
        assertEquals(LIST_B, mapper.readTree(other.body()).get(0).get("id").asString());
        var empty = get("/api/v1/zaehlerlisten", token("demo-ohne-listen")); assertEquals(200, empty.statusCode()); assertEquals("[]", empty.body());
    }

    static Stream<Arguments> ownPaths() {
        return Stream.of(Arguments.of("/api/v1/zaehlerlisten"), Arguments.of(sessions(LIST_A)),
                Arguments.of(sheets(SESSION_A)), Arguments.of(detail(SESSION_A, SHEET_A)));
    }

    @ParameterizedTest @MethodSource("ownPaths")
    void everyBusinessEndpointRequiresTokenAndExcludesAdmin(String path) throws Exception {
        assertEquals(401, get(path, null).statusCode());
        assertEquals(403, get(path, token("demo-admin")).statusCode());
        assertEquals(200, get(path, keys.token()).statusCode());
    }

    @Test
    void ownSessionsSheetsAndResultsAreReadableOnlyByCurrentAssignedCounter() throws Exception {
        var sessions = get(sessions(LIST_A), keys.token()); assertEquals(200, sessions.statusCode());
        var json = mapper.readTree(sessions.body()); assertEquals(2, json.size()); assertEquals(11, json.get(0).get("rundeNr").asInt());
        assertEquals(SESSION_A, json.get(0).get("id").asString());
        var sheets = get(sheets(SESSION_A), keys.token()); assertEquals(200, sheets.statusCode()); assertEquals(2, mapper.readTree(sheets.body()).size());
        var detail = get(detail(SESSION_A, SHEET_A), keys.token()); assertEquals(200, detail.statusCode());
        var positions = mapper.readTree(detail.body()).get("positionen"); assertEquals(2, positions.size());
        assertEquals(1, positions.get(0).get("positionsnummer").asInt());
        assertEquals(0, positions.get(0).get("ergebnis").get("zaehlmenge").asInt());
        assertEquals(3, positions.get(0).get("ergebnis").get("revision").asLong());
        assertTrue(positions.get(1).get("ergebnis").get("zaehlmenge").isNull());
        assertEquals(200, get(detail(SESSION_B, SHEET_B), token("demo-zaehler-b")).statusCode());
    }

    static Stream<Arguments> hiddenPaths() {
        return Stream.of(Arguments.of(sessions(LIST_B)), Arguments.of(sessions(UNKNOWN)),
                Arguments.of(sheets(SESSION_B)), Arguments.of(sheets(UNKNOWN)),
                Arguments.of(detail(SESSION_B, SHEET_B)), Arguments.of(detail(SESSION_A, SHEET_B)),
                Arguments.of(detail(SESSION_A2, SHEET_A)), Arguments.of(detail(SESSION_A, UNKNOWN)),
                Arguments.of(detail(UNKNOWN, SHEET_A)));
    }

    @ParameterizedTest @MethodSource("hiddenPaths")
    void foreignMissingAndMismatchedResourcesHaveSame404WithoutForeignData(String path) throws Exception {
        var response = get(path, keys.token()); assertEquals(404, response.statusCode());
        assertEquals(mapper.readTree("""
                {"status":404,"code":"RESSOURCE_NICHT_GEFUNDEN","detail":"Ressource nicht gefunden."}
                """), mapper.readTree(response.body()));
    }

    static Stream<Arguments> malformedPaths() {
        return Stream.of(Arguments.of(sessions("keine-uuid")), Arguments.of(sheets("keine-uuid")),
                Arguments.of(detail("keine-uuid", SHEET_A)), Arguments.of(detail(SESSION_A, "keine-uuid")));
    }

    @ParameterizedTest @MethodSource("malformedPaths")
    void malformedUuidReturns400WithoutEchoingItsValue(String path) throws Exception {
        var response = get(path, keys.token()); assertEquals(400, response.statusCode());
        assertEquals("UNGUELTIGER_PARAMETER", mapper.readTree(response.body()).get("code").asString());
        assertFalse(response.body().contains("keine-uuid"));
    }

    @Test
    void roleChangeAndDeactivationAreEffectiveForPreviouslyIssuedToken() throws Exception {
        String original = keys.token(); assertEquals(200, get("/api/v1/zaehlerlisten", original).statusCode());
        jdbc.update("UPDATE benutzer SET rolle='ADMIN' WHERE id='50000000-0000-4000-8000-000000000001'");
        assertEquals(403, get("/api/v1/zaehlerlisten", original).statusCode());
        jdbc.update("UPDATE benutzer SET rolle='ZAEHLER', aktiv=false WHERE id='50000000-0000-4000-8000-000000000001'");
        assertEquals(401, get("/api/v1/zaehlerlisten", original).statusCode());
    }

    @Test
    @Transactional
    void cachedUserEntityCannotHideCurrentServerRoleFromInternalReadService() {
        UUID id = UUID.fromString("50000000-0000-4000-8000-000000000001");
        var cached = em.find(BenutzerEntity.class, id); assertEquals(BenutzerRolle.ZAEHLER, cached.getRolle());
        jdbc.update("UPDATE benutzer SET rolle='ADMIN' WHERE id=?", id);
        assertEquals(BenutzerRolle.ZAEHLER, cached.getRolle());
        var vorher = SecurityContextHolder.getContext(); var kontrollierterTestKontext = SecurityContextHolder.createEmptyContext();
        kontrollierterTestKontext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new InventurPrincipal(id), null, java.util.List.of()));
        try {
            SecurityContextHolder.setContext(kontrollierterTestKontext);
            assertThrows(AccessDeniedException.class, () -> listenService.findeAlle());
        } finally { SecurityContextHolder.setContext(vorher); }
    }

    @Test
    void reassignmentRevokesEntireOldListAccessAndPreservesResults() throws Exception {
        String original = keys.token(); assertEquals(200, get(detail(SESSION_A, SHEET_A), original).statusCode());
        jdbc.update("UPDATE zaehlerliste SET aktueller_zaehler_id='50000000-0000-4000-8000-000000000002', zuweisung_version=2 WHERE id=?::uuid", LIST_A);
        assertEquals("[]", get("/api/v1/zaehlerlisten", original).body());
        for (String path : new String[] {sessions(LIST_A), sheets(SESSION_A), detail(SESSION_A, SHEET_A)}) {
            assertEquals(404, get(path, original).statusCode());
            assertEquals(200, get(path, token("demo-zaehler-b")).statusCode());
        }
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM zaehlergebnis", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sync_request", Integer.class));
    }

    @Test
    void freeClientIdentityAndRoleClaimsCannotChangeListScope() throws Exception {
        String signed = keys.token(Map.of("benutzerId", "50000000-0000-4000-8000-000000000002", "roles", java.util.List.of("ADMIN")));
        var result = get("/api/v1/zaehlerlisten", signed);
        assertEquals(200, result.statusCode()); assertEquals(1, mapper.readTree(result.body()).size());
        assertEquals(LIST_A, mapper.readTree(result.body()).get(0).get("id").asString());
    }

    @Test
    void existingOwnedListWithoutSessionsReturnsEmptyArray() throws Exception {
        String id = "10000000-0000-4000-8000-000000000003";
        jdbc.update("INSERT INTO zaehlerliste(id, code, bezeichnung, aktueller_zaehler_id, zuweisung_version) VALUES (?::uuid, 'DEMO-03', 'Demo-Leerliste', '50000000-0000-4000-8000-000000000001', 1)", id);
        try { var result = get(sessions(id), keys.token()); assertEquals(200, result.statusCode()); assertEquals("[]", result.body()); }
        finally { jdbc.update("DELETE FROM zaehlerliste WHERE id=?::uuid", id); }
    }

    @Test
    void foreignDetailLoadsNoArticlesPositionsOrResults() throws Exception {
        var statistics = emf.unwrap(SessionFactory.class).getStatistics(); statistics.clear();
        assertEquals(404, get(detail(SESSION_B, SHEET_B), keys.token()).statusCode());
        for (String entity : new String[] {"ArtikelEntity", "ZaehlpositionEntity", "ZaehlergebnisEntity"}) {
            assertEquals(0, statistics.getEntityStatistics("de.pia.inventur.backend.entity." + entity).getLoadCount());
        }
        assertEquals(3, statistics.getPrepareStatementCount()); // JWT-Zuordnung, Rolle, berechtigtes Blatt.
    }

    @Test
    void detailQueryCountIsConstantAndEmptySheetSkipsResultQuery() throws Exception {
        var statistics = emf.unwrap(SessionFactory.class).getStatistics(); statistics.clear();
        assertEquals(200, get(detail(SESSION_A, SHEET_A), keys.token()).statusCode());
        long small = statistics.getPrepareStatementCount(); assertEquals(5, small);
        for (int number=20; number<30; number++) {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO zaehlposition(id, zaehlblatt_id, artikel_id, positionsnummer) VALUES (?, ?::uuid, '83000000-0000-4000-8000-000000000001', ?)", id, SHEET_A, number);
            jdbc.update("INSERT INTO zaehlergebnis(id, zaehlposition_id, zaehlmenge, revision) VALUES (?, ?, 1, 1)", UUID.randomUUID(), id);
        }
        try {
            statistics.clear(); var result = get(detail(SESSION_A, SHEET_A), keys.token()); assertEquals(200, result.statusCode());
            assertEquals(12, mapper.readTree(result.body()).get("positionen").size()); assertEquals(small, statistics.getPrepareStatementCount());
        } finally {
            jdbc.update("DELETE FROM zaehlergebnis WHERE zaehlposition_id IN (SELECT id FROM zaehlposition WHERE zaehlblatt_id=?::uuid AND positionsnummer>=20)", SHEET_A);
            jdbc.update("DELETE FROM zaehlposition WHERE zaehlblatt_id=?::uuid AND positionsnummer>=20", SHEET_A);
        }
        statistics.clear(); assertEquals(200, get(detail(SESSION_A, "82000000-0000-4000-8000-000000000003"), keys.token()).statusCode());
        assertEquals(4, statistics.getPrepareStatementCount());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10)).GET();
            if (token != null) request.header("Authorization", "Bearer " + token);
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    private static String sessions(String id) { return "/api/v1/zaehlerlisten/" + id + "/sitzungen"; }
    private static String sheets(String id) { return "/api/v1/sitzungen/" + id + "/blaetter"; }
    private static String detail(String session, String sheet) { return sheets(session) + "/" + sheet; }
    private String token(String subject) { return keys.token(Map.of("sub", subject)); }
}
