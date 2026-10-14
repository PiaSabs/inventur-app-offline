package de.pia.inventur.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.sql.SQLException;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import de.pia.inventur.backend.entity.BenutzerEntity;
import de.pia.inventur.backend.entity.BenutzerRolle;
import de.pia.inventur.backend.entity.ZaehlerlisteEntity;
import de.pia.inventur.backend.repository.ZaehlerlistenRepository;
import de.pia.inventur.backend.entity.ZaehlsitzungEntity;
import de.pia.inventur.backend.entity.ZaehlsitzungStatus;
import de.pia.inventur.backend.repository.ZaehlsitzungenRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Sql(scripts = "/test-demo.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class PostgresIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("inventur_test")
            .withUsername("inventur_test")
            .withPassword(UUID.randomUUID().toString());

    @Autowired
    private MockMvc mockMvc;

    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    @Test
    void realHttpServerUsesProductionSecurityWithoutTestAuthentication() throws Exception {
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            var health = client.send(java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:" + port + "/api/v1/health"))
                    .timeout(java.time.Duration.ofSeconds(10)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode()); assertEquals("{\"status\":\"UP\"}", health.body());
            var lists = client.send(java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:" + port + "/api/v1/zaehlerlisten"))
                    .timeout(java.time.Duration.ofSeconds(10)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(401, lists.statusCode());
            assertEquals("Bearer", lists.headers().firstValue("WWW-Authenticate").orElseThrow());
            assertEquals(401, JsonMapper.builder().build().readTree(lists.body()).get("status").asInt());
        }
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ZaehlerlistenRepository repository;

    @Autowired
    private ZaehlsitzungenRepository sitzungen;

    private static final UUID LISTE_A = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID LISTE_B = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final LocalDate ZAEHLTAG = LocalDate.of(2026, 10, 13);
    private static final Instant START = Instant.parse("2026-10-13T08:00:00Z");

    @Autowired
    private DataSource dataSource;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void flywayAndJpaUseOnlyTheIsolatedTestDatabase() {
        assertEquals("inventur_test", jdbc.queryForObject("SELECT current_database()", String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class));
        assertEquals(1487691541, jdbc.queryForObject(
                "SELECT checksum FROM flyway_schema_history WHERE version = '1'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '2' AND success", Integer.class));
        assertEquals(1076962244, jdbc.queryForObject(
                "SELECT checksum FROM flyway_schema_history WHERE version = '2'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '3' AND success", Integer.class));
        assertEquals(-1316545777, jdbc.queryForObject(
                "SELECT checksum FROM flyway_schema_history WHERE version = '3'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '4' AND success", Integer.class));
        assertEquals(1488863753, jdbc.queryForObject(
                "SELECT checksum FROM flyway_schema_history WHERE version = '4'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '5' AND success", Integer.class));
        assertEquals(-667264077, jdbc.queryForObject(
                "SELECT checksum FROM flyway_schema_history WHERE version = '5'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '6' AND success", Integer.class));
        assertEquals(-1679719093, jdbc.queryForObject(
                "SELECT checksum FROM flyway_schema_history WHERE version = '6'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '7' AND success", Integer.class));
        assertEquals("uuid", jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'zaehlerliste' AND column_name = 'id'",
                String.class));
        assertEquals(2, repository.findAllByOrderByCodeAsc().size());
        assertEquals("DEMO-01", repository.findAllByOrderByCodeAsc().getFirst().getCode());
    }

    @Test
    void zaehlerlistenEndpointReadsBothDemoEntriesFromPostgres() throws Exception {
        var response = get("/api/v1/zaehlerlisten");
        assertEquals(200, response.statusCode());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(""));
        var mapper = JsonMapper.builder().build();
        assertEquals(mapper.readTree("""
                [
                  {"id":"10000000-0000-4000-8000-000000000001",
                   "code":"DEMO-01","bezeichnung":"Demo-Zaehlerliste Sonnenlager"},
                  {"id":"10000000-0000-4000-8000-000000000002",
                   "code":"DEMO-02","bezeichnung":"Demo-Zaehlerliste Mondlager"}
                ]
                """), mapper.readTree(response.body()));
    }

    @Test
    void healthEndpointStillReturnsUp() throws Exception {
        var response = get("/api/v1/health");
        assertEquals(200, response.statusCode());
        assertEquals("{\"status\":\"UP\"}", response.body());
    }

    @Test
    void schemaRejectsBlankTextAndDuplicateCodes() throws Exception {
        assertEquals("inventur_test", jdbc.queryForObject("SELECT current_database()", String.class));
        String[] values = {
                "'20000000-0000-4000-8000-000000000001', '  ', 'Test'",
                "'20000000-0000-4000-8000-000000000002', 'TEST', '  '",
                "'20000000-0000-4000-8000-000000000003', 'DEMO-01', 'Test'"
        };
        for (String value : values) {
            try (var connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (var statement = connection.createStatement()) {
                    assertThrows(SQLException.class, () -> statement.executeUpdate(
                            "INSERT INTO zaehlerliste (id, code, bezeichnung) VALUES (" + value + ")"));
                } finally {
                    connection.rollback();
                }
            }
        }
        assertEquals(2, repository.count());
    }

    @Test
    @Transactional
    void benutzerCanBePersistedAndLoadedThroughJpa() {
        UUID id = UUID.randomUUID();
        entityManager.persist(new BenutzerEntity(id, "Demo-Testbenutzer", BenutzerRolle.ZAEHLER, false));
        entityManager.flush();
        entityManager.clear();

        BenutzerEntity loaded = entityManager.find(BenutzerEntity.class, id);
        assertEquals(id, loaded.getId());
        assertEquals("Demo-Testbenutzer", loaded.getAnzeigename());
        assertEquals(BenutzerRolle.ZAEHLER, loaded.getRolle());
        assertFalse(loaded.isAktiv());
    }

    @Test
    @Transactional
    void bothListsReferenceTheSameActiveDemoZaehler() {
        var lists = repository.findAllByOrderByCodeAsc();
        assertEquals(2, lists.size());
        for (var list : lists) {
            assertEquals(UUID.fromString("50000000-0000-4000-8000-000000000001"),
                    list.getAktuellerZaehler().getId());
            assertEquals("Demo-Zaehler A", list.getAktuellerZaehler().getAnzeigename());
            assertEquals(BenutzerRolle.ZAEHLER, list.getAktuellerZaehler().getRolle());
            assertTrue(list.getAktuellerZaehler().isAktiv());
            assertEquals(1, list.getZuweisungVersion());
        }
        assertEquals(BenutzerRolle.ADMIN, entityManager.find(BenutzerEntity.class,
                UUID.fromString("50000000-0000-4000-8000-000000000003")).getRolle());
    }

    @Test
    @Transactional
    void draftListMayHaveNoAssignedZaehler() {
        UUID id = UUID.randomUUID();
        entityManager.persist(new ZaehlerlisteEntity(id, "DEMO-DRAFT", "Demo-Entwurf"));
        entityManager.flush();
        entityManager.clear();

        ZaehlerlisteEntity loaded = entityManager.find(ZaehlerlisteEntity.class, id);
        assertNull(loaded.getAktuellerZaehler());
        assertEquals(0, loaded.getZuweisungVersion());
        assertEquals("0", jdbc.queryForObject("SELECT column_default FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name='zaehlerliste' "
                + "AND column_name='zuweisung_version'", String.class));
    }

    @Test
    void foreignKeyRoleAndVersionConstraintsRejectInvalidValues() throws Exception {
        assertEquals("inventur_test", jdbc.queryForObject("SELECT current_database()", String.class));
        String[][] invalid = {
                {"UPDATE zaehlerliste SET aktueller_zaehler_id = '90000000-0000-4000-8000-000000000001' "
                        + "WHERE code = 'DEMO-01'", "23503"},
                {"UPDATE benutzer SET rolle = 'UNGUELTIG' "
                        + "WHERE id = '50000000-0000-4000-8000-000000000001'", "23514"},
                {"UPDATE benutzer SET rolle = '' "
                        + "WHERE id = '50000000-0000-4000-8000-000000000001'", "23514"},
                {"UPDATE zaehlerliste SET zuweisung_version = -1 WHERE code = 'DEMO-01'", "23514"},
                {"UPDATE benutzer SET anzeigename = '  ' "
                        + "WHERE id = '50000000-0000-4000-8000-000000000001'", "23514"},
                {"UPDATE benutzer SET aktiv = NULL "
                        + "WHERE id = '50000000-0000-4000-8000-000000000001'", "23502"}
        };
        for (String[] entry : invalid) {
            try (var connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (var statement = connection.createStatement()) {
                    SQLException error = assertThrows(SQLException.class, () -> statement.executeUpdate(entry[0]));
                    assertEquals(entry[1], error.getSQLState());
                } finally {
                    connection.rollback();
                }
            }
        }
    }

    @Test
    @Transactional
    void sessionCanBePersistedAndLoadedThroughJpa() {
        UUID id = UUID.randomUUID();
        sitzungen.saveAndFlush(new ZaehlsitzungEntity(id, repository.findById(LISTE_A).orElseThrow(),
                ZAEHLTAG, 1, ZaehlsitzungStatus.OFFEN, 1, START, null));
        entityManager.clear();

        var loaded = sitzungen.findById(id).orElseThrow();
        assertEquals(id, loaded.getId());
        assertFalse(entityManager.getEntityManagerFactory().getPersistenceUnitUtil()
                .isLoaded(loaded, "zaehlerliste"));
        assertEquals("DEMO-01", loaded.getZaehlerliste().getCode());
        assertEquals(ZAEHLTAG, loaded.getZaehltag());
        assertEquals(1, loaded.getRundeNr());
        assertEquals(ZaehlsitzungStatus.OFFEN, loaded.getStatus());
        assertEquals(1, loaded.getStatusVersion());
        assertEquals(START, loaded.getGestartetAm());
        assertNull(loaded.getAbgeschlossenAm());
    }

    @Test
    @Transactional
    void historicalRoundsRemainAndSessionsAreOrderedByDayAndRound() {
        var list = repository.findById(LISTE_A).orElseThrow();
        for (int round : new int[] {2, 1}) {
            sitzungen.save(new ZaehlsitzungEntity(UUID.randomUUID(), list, ZAEHLTAG, round,
                    ZaehlsitzungStatus.ABGESCHLOSSEN, 2, START, START.plusSeconds(3600)));
        }
        sitzungen.save(new ZaehlsitzungEntity(UUID.randomUUID(), list, ZAEHLTAG.plusDays(1), 1,
                ZaehlsitzungStatus.OFFEN, 1, START.plusSeconds(86400), null));
        sitzungen.flush();
        entityManager.clear();

        var loaded = sitzungen.findAllByZaehlerliste_IdOrderByZaehltagAscRundeNrAsc(LISTE_A);
        assertEquals(3, loaded.size());
        assertEquals(1, loaded.get(0).getRundeNr());
        assertEquals(2, loaded.get(1).getRundeNr());
        assertEquals(ZAEHLTAG, loaded.get(1).getZaehltag());
        assertEquals(ZAEHLTAG.plusDays(1), loaded.get(2).getZaehltag());
        assertEquals(ZaehlsitzungStatus.ABGESCHLOSSEN, loaded.get(0).getStatus());
        assertEquals(ZaehlsitzungStatus.ABGESCHLOSSEN, loaded.get(1).getStatus());
        assertEquals(START.plusSeconds(3600), loaded.get(0).getAbgeschlossenAm());
        assertEquals(ZaehlsitzungStatus.OFFEN, loaded.get(2).getStatus());
    }

    @Test
    void duplicateRoundOnSameDayIsRejected() throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                insertSession(connection, LISTE_A, ZAEHLTAG, 1, "ABGESCHLOSSEN", 1);
                var error = assertThrows(SQLException.class,
                        () -> insertSession(connection, LISTE_A, ZAEHLTAG, 1, "ABGESCHLOSSEN", 1));
                assertEquals("23505", error.getSQLState());
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void secondOpenSessionIsRejectedEvenOnAnotherDay() throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                insertSession(connection, LISTE_A, ZAEHLTAG, 1, "OFFEN", 1);
                var error = assertThrows(SQLException.class,
                        () -> insertSession(connection, LISTE_A, ZAEHLTAG.plusDays(1), 1, "OFFEN", 1));
                assertEquals("23505", error.getSQLState());
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    @Transactional
    void differentListsMayEachHaveAnOpenSession() {
        for (UUID listId : new UUID[] {LISTE_A, LISTE_B}) {
            sitzungen.save(new ZaehlsitzungEntity(UUID.randomUUID(), repository.findById(listId).orElseThrow(),
                    ZAEHLTAG, 1, ZaehlsitzungStatus.OFFEN, 1, START, null));
        }
        sitzungen.flush();
        entityManager.clear();
        for (UUID listId : new UUID[] {LISTE_A, LISTE_B}) {
            var loaded = sitzungen.findAllByZaehlerliste_IdOrderByZaehltagAscRundeNrAsc(listId);
            assertEquals(1, loaded.size());
            assertEquals(listId, loaded.getFirst().getZaehlerliste().getId());
            assertEquals(ZaehlsitzungStatus.OFFEN, loaded.getFirst().getStatus());
        }
    }

    @Test
    void invalidSessionStatusRoundAndVersionAreRejected() throws Exception {
        assertRejectedSession(LISTE_A, 1, "UNGUELTIG", 1, "23514");
        assertRejectedSession(LISTE_A, 1, "", 1, "23514");
        assertRejectedSession(LISTE_A, 0, "OFFEN", 1, "23514");
        assertRejectedSession(LISTE_A, -1, "OFFEN", 1, "23514");
        assertRejectedSession(LISTE_A, 1, "OFFEN", 0, "23514");
        assertRejectedSession(LISTE_A, 1, "OFFEN", -1, "23514");
        assertRejectedSession(LISTE_A, 1, null, 1, "23502");
        assertRejectedSession(null, 1, "OFFEN", 1, "23502");
    }

    @Test
    void sessionRequiresExistingListAndPreventsDeletingItsHistory() throws Exception {
        assertRejectedSession(UUID.randomUUID(), 1, "OFFEN", 1, "23503");
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                insertSession(connection, LISTE_A, ZAEHLTAG, 1, "ABGESCHLOSSEN", 1);
                try (var statement = connection.prepareStatement("DELETE FROM zaehlerliste WHERE id = ?")) {
                    statement.setObject(1, LISTE_A);
                    var error = assertThrows(SQLException.class, statement::executeUpdate);
                    assertEquals("23503", error.getSQLState());
                }
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    @Sql(scripts = "/test-sitzungen.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
    @Sql(statements = """
            DELETE FROM zaehlsitzung WHERE id IN (
                '60000000-0000-4000-8000-000000000001',
                '60000000-0000-4000-8000-000000000002',
                '60000000-0000-4000-8000-000000000003');
            """, executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
    void sessionsEndpointReturnsSortedDtosWithDatesInstantsAndNull() throws Exception {
        assertEquals("inventur_test", jdbc.queryForObject("SELECT current_database()", String.class));
        var response = get("/api/v1/zaehlerlisten/" + LISTE_A + "/sitzungen");
        assertEquals(200, response.statusCode());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(""));
        var mapper = JsonMapper.builder().build();
        assertEquals(mapper.readTree("""
                [
                  {"id":"60000000-0000-4000-8000-000000000001",
                   "zaehlerlisteId":"10000000-0000-4000-8000-000000000001",
                   "zaehltag":"2026-10-13","rundeNr":1,"status":"ABGESCHLOSSEN",
                   "statusVersion":2,"gestartetAm":"2026-10-13T08:00:00Z",
                   "abgeschlossenAm":"2026-10-13T09:00:00Z"},
                  {"id":"60000000-0000-4000-8000-000000000002",
                   "zaehlerlisteId":"10000000-0000-4000-8000-000000000001",
                   "zaehltag":"2026-10-13","rundeNr":2,"status":"ABGESCHLOSSEN",
                   "statusVersion":2,"gestartetAm":"2026-10-13T10:00:00Z",
                   "abgeschlossenAm":"2026-10-13T11:00:00Z"},
                  {"id":"60000000-0000-4000-8000-000000000003",
                   "zaehlerlisteId":"10000000-0000-4000-8000-000000000001",
                   "zaehltag":"2026-10-14","rundeNr":1,"status":"OFFEN",
                   "statusVersion":1,"gestartetAm":"2026-10-14T08:00:00Z",
                   "abgeschlossenAm":null}
                ]
                """), mapper.readTree(response.body()));
        // Die Sitzungsauswahl darf keine Datensaetze einer anderen Liste liefern.
        assertEquals("[]", get("/api/v1/zaehlerlisten/" + LISTE_B + "/sitzungen").body());
    }

    @Test
    void sessionsEndpointReturnsEmptyArrayForExistingListWithoutSessions() throws Exception {
        var response = get("/api/v1/zaehlerlisten/" + LISTE_A + "/sitzungen");
        assertEquals(200, response.statusCode());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(""));
        assertEquals("[]", response.body());
    }

    @Test
    void sessionsEndpointReturnsNotFoundForUnknownList() throws Exception {
        var response = get("/api/v1/zaehlerlisten/90000000-0000-4000-8000-000000000001/sitzungen");
        assertEquals(404, response.statusCode());
    }

    @Test
    void sessionsEndpointRejectsMalformedUuid() throws Exception {
        assertEquals(400, get("/api/v1/zaehlerlisten/keine-uuid/sitzungen").statusCode());
    }

    private void assertRejectedSession(UUID listId, int round, String status, long version,
            String sqlState) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var error = assertThrows(SQLException.class,
                        () -> insertSession(connection, listId, ZAEHLTAG, round, status, version));
                assertEquals(sqlState, error.getSQLState());
            } finally {
                connection.rollback();
            }
        }
    }

    private void insertSession(Connection connection, UUID listId, LocalDate day, int round,
            String status, long version) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO zaehlsitzung
                    (id, zaehlerliste_id, zaehltag, runde_nr, status, status_version, gestartet_am, abgeschlossen_am)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, listId);
            statement.setObject(3, day);
            statement.setInt(4, round);
            statement.setString(5, status);
            statement.setLong(6, version);
            statement.setObject(7, OffsetDateTime.ofInstant(START, ZoneOffset.UTC));
            statement.setObject(8, "ABGESCHLOSSEN".equals(status)
                    ? OffsetDateTime.ofInstant(START.plusSeconds(3600), ZoneOffset.UTC) : null);
            statement.executeUpdate();
        }
    }

    private AuthentifizierteTestAnfragen.Antwort get(String path) throws Exception {
        return AuthentifizierteTestAnfragen.lese(mockMvc, path);
    }
}
