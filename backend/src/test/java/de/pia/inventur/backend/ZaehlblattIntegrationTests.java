package de.pia.inventur.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import de.pia.inventur.backend.entity.BenutzerEntity;
import de.pia.inventur.backend.entity.ZaehlblattEntity;
import de.pia.inventur.backend.entity.ZaehlblattStatus;
import de.pia.inventur.backend.entity.ZaehlsitzungEntity;
import de.pia.inventur.backend.repository.ZaehlblaetterRepository;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Sql(scripts = "/test-demo.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Sql(scripts = "/test-blatt-sitzungen.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(scripts = "/cleanup-test-blaetter.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class ZaehlblattIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("inventur_test")
            .withUsername("inventur_test")
            .withPassword(UUID.randomUUID().toString());

    private static final UUID SITZUNG_A = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final UUID SITZUNG_B = UUID.fromString("70000000-0000-4000-8000-000000000002");
    private static final UUID BENUTZER_B = UUID.fromString("50000000-0000-4000-8000-000000000002");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ZaehlblaetterRepository repository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;
    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void flywayV4AndHibernateValidateUseIsolatedPostgres() {
        assertEquals("inventur_test", jdbc.queryForObject("SELECT current_database()", String.class));
        assertEquals(9, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '4' AND success", Integer.class));
        assertEquals("timestamp with time zone", jdbc.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema='public' AND table_name='zaehlblatt' AND column_name='abgeschlossen_am'
                """, String.class));
        // Der Spring-Kontext startet nur nach erfolgreicher Hibernate-Schemavalidierung.
        assertTrue(entityManager.getEntityManagerFactory().isOpen());
    }

    @Test
    @Transactional
    void sheetCanBeStoredAndLoadedWithLazyRelationshipsAndRequestId() {
        UUID id = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Instant closed = Instant.parse("2026-10-13T09:00:00Z");
        repository.saveAndFlush(new ZaehlblattEntity(id,
                entityManager.find(ZaehlsitzungEntity.class, SITZUNG_A), "1",
                ZaehlblattStatus.ABGESCHLOSSEN, 2, closed,
                entityManager.find(BenutzerEntity.class, BENUTZER_B), null));
        // Ab V7 braucht der Blattverweis einen existierenden Request desselben Blatts.
        jdbc.update("""
                INSERT INTO sync_request (request_id, urheber_id, operation, zaehlerliste_id,
                    zaehlsitzung_id, zaehlblatt_id, zuweisung_version, normalisierter_inhalt,
                    inhalt_fingerprint, inhalt_format_version, status, eingegangen_am)
                VALUES (?, ?, 'BLATT_ABSCHLIESSEN', '10000000-0000-4000-8000-000000000001',
                    ?, ?, 1, '{}', encode(sha256(convert_to('{}', 'UTF8')), 'hex'), 1,
                    'EINGEGANGEN', '2026-10-13T09:00:00Z')
                """, requestId, BENUTZER_B, SITZUNG_A, id);
        jdbc.update("UPDATE zaehlblatt SET letzter_status_request_id=? WHERE id=?", requestId, id);
        entityManager.clear();

        var loaded = repository.findById(id).orElseThrow();
        var util = entityManager.getEntityManagerFactory().getPersistenceUnitUtil();
        assertFalse(util.isLoaded(loaded, "zaehlsitzung"));
        assertFalse(util.isLoaded(loaded, "geschlossenVon"));
        assertEquals(id, loaded.getId());
        assertEquals(SITZUNG_A, loaded.getZaehlsitzung().getId());
        assertEquals("1", loaded.getBlattnummer());
        assertEquals(ZaehlblattStatus.ABGESCHLOSSEN, loaded.getStatus());
        assertEquals(2, loaded.getStatusVersion());
        assertEquals(closed, loaded.getAbgeschlossenAm());
        assertEquals("Demo-Zaehler B", loaded.getGeschlossenVon().getAnzeigename());
        assertEquals(requestId, loaded.getLetzterStatusRequestId());
    }

    @Test
    @Transactional
    void multipleSheetsAndSameNumberInAnotherSessionAreAllowedWithTextOrdering() {
        var sessionA = entityManager.find(ZaehlsitzungEntity.class, SITZUNG_A);
        var sessionB = entityManager.find(ZaehlsitzungEntity.class, SITZUNG_B);
        for (String number : List.of("2", "10", "1")) {
            repository.save(new ZaehlblattEntity(UUID.randomUUID(), sessionA, number,
                    ZaehlblattStatus.OFFEN, 1, null, null, null));
        }
        repository.save(new ZaehlblattEntity(UUID.randomUUID(), sessionB, "1",
                ZaehlblattStatus.OFFEN, 1, null, null, null));
        repository.flush();
        entityManager.clear();
        var sheets = repository.findAllByZaehlsitzung_IdOrderByBlattnummerAsc(SITZUNG_A);
        assertEquals(List.of("1", "10", "2"), sheets.stream().map(ZaehlblattEntity::getBlattnummer).toList());
        assertNull(sheets.getFirst().getAbgeschlossenAm());
        assertNull(sheets.getFirst().getGeschlossenVon());
        assertNull(sheets.getFirst().getLetzterStatusRequestId());
        assertEquals(1, repository.findAllByZaehlsitzung_IdOrderByBlattnummerAsc(SITZUNG_B).size());
    }

    @Test
    void duplicateSheetNumberInSameSessionIsRejected() throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                insertSheet(connection, SITZUNG_A, "1", "OFFEN", 1, null);
                var error = assertThrows(SQLException.class,
                        () -> insertSheet(connection, SITZUNG_A, "1", "OFFEN", 1, null));
                assertEquals("23505", error.getSQLState());
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void invalidSheetNumbersStatusesAndVersionsAreRejected() throws Exception {
        assertRejectedSheet(SITZUNG_A, "", "OFFEN", 1, null, "23514");
        assertRejectedSheet(SITZUNG_A, "  ", "OFFEN", 1, null, "23514");
        assertRejectedSheet(SITZUNG_A, null, "OFFEN", 1, null, "23502");
        assertRejectedSheet(SITZUNG_A, "1", "UNGUELTIG", 1, null, "23514");
        assertRejectedSheet(SITZUNG_A, "1", null, 1, null, "23502");
        assertRejectedSheet(SITZUNG_A, "1", "OFFEN", 0, null, "23514");
        assertRejectedSheet(SITZUNG_A, "1", "OFFEN", -1, null, "23514");
    }

    @Test
    void foreignKeysProtectSessionUserAndHistoricalSheets() throws Exception {
        assertRejectedSheet(UUID.randomUUID(), "1", "OFFEN", 1, null, "23503");
        assertRejectedSheet(null, "1", "OFFEN", 1, null, "23502");
        assertRejectedSheet(SITZUNG_A, "1", "ABGESCHLOSSEN", 2, UUID.randomUUID(), "23503");
        for (String sql : List.of("DELETE FROM zaehlsitzung WHERE id = ?", "DELETE FROM benutzer WHERE id = ?")) {
            try (var connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    insertSheet(connection, SITZUNG_A, "1", "ABGESCHLOSSEN", 2, BENUTZER_B);
                    try (var statement = connection.prepareStatement(sql)) {
                        statement.setObject(1, sql.contains("zaehlsitzung") ? SITZUNG_A : BENUTZER_B);
                        var error = assertThrows(SQLException.class, statement::executeUpdate);
                        assertEquals("23503", error.getSQLState());
                    }
                } finally {
                    connection.rollback();
                }
            }
        }
    }

    @Test
    void sheetsEndpointReturnsExactDtosInTextOrderAndOnlyForRequestedSession() throws Exception {
        jdbc.update("""
                INSERT INTO zaehlblatt (id, zaehlsitzung_id, blattnummer, status, status_version, abgeschlossen_am)
                VALUES
                    ('80000000-0000-4000-8000-000000000003', ?, '2', 'OFFEN', 1, NULL),
                    ('80000000-0000-4000-8000-000000000002', ?, '10', 'OFFEN', 1, NULL),
                    ('80000000-0000-4000-8000-000000000001', ?, '1', 'ABGESCHLOSSEN', 2, '2026-10-13T11:00:00+02:00'),
                    ('80000000-0000-4000-8000-000000000004', ?, '1', 'OFFEN', 1, NULL)
                """, SITZUNG_A, SITZUNG_A, SITZUNG_A, SITZUNG_B);
        var response = get(SITZUNG_A.toString());
        assertEquals(200, response.statusCode());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(""));
        var mapper = JsonMapper.builder().build();
        assertEquals(mapper.readTree("""
                [
                  {"id":"80000000-0000-4000-8000-000000000001",
                   "sitzungId":"70000000-0000-4000-8000-000000000001","blattnummer":"1",
                   "status":"ABGESCHLOSSEN","statusVersion":2,"abgeschlossenAm":"2026-10-13T09:00:00Z"},
                  {"id":"80000000-0000-4000-8000-000000000002",
                   "sitzungId":"70000000-0000-4000-8000-000000000001","blattnummer":"10",
                   "status":"OFFEN","statusVersion":1,"abgeschlossenAm":null},
                  {"id":"80000000-0000-4000-8000-000000000003",
                   "sitzungId":"70000000-0000-4000-8000-000000000001","blattnummer":"2",
                   "status":"OFFEN","statusVersion":1,"abgeschlossenAm":null}
                ]
                """), mapper.readTree(response.body()));
        assertEquals(1, mapper.readTree(get(SITZUNG_B.toString()).body()).size());
    }

    @Test
    void existingSessionWithoutSheetsReturnsEmptyArray() throws Exception {
        var response = get(SITZUNG_A.toString());
        assertEquals(200, response.statusCode());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(""));
        assertEquals("[]", response.body());
    }

    @Test
    void unknownSessionReturns404() throws Exception {
        assertEquals(404, get("90000000-0000-4000-8000-000000000001").statusCode());
    }

    @Test
    void malformedSessionUuidReturns400() throws Exception {
        assertEquals(400, get("keine-uuid").statusCode());
    }

    private void assertRejectedSheet(UUID sessionId, String number, String status, long version,
            UUID userId, String sqlState) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var error = assertThrows(SQLException.class,
                        () -> insertSheet(connection, sessionId, number, status, version, userId));
                assertEquals(sqlState, error.getSQLState());
            } finally {
                connection.rollback();
            }
        }
    }

    private void insertSheet(Connection connection, UUID sessionId, String number, String status,
            long version, UUID userId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO zaehlblatt (id, zaehlsitzung_id, blattnummer, status, status_version, geschlossen_von)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, sessionId);
            statement.setString(3, number);
            statement.setString(4, status);
            statement.setLong(5, version);
            statement.setObject(6, userId);
            statement.executeUpdate();
        }
    }

    private AuthentifizierteTestAnfragen.Antwort get(String sessionId) throws Exception {
        return AuthentifizierteTestAnfragen.lese(mockMvc, "/api/v1/sitzungen/" + sessionId + "/blaetter");
    }
}
