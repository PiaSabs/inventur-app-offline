package de.pia.inventur.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import de.pia.inventur.backend.entity.*;
import de.pia.inventur.backend.repository.SyncRequestsRepository;
import de.pia.inventur.backend.repository.ErgebnisHistorieRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
@Sql(scripts = {"/test-demo.sql", "/test-blatt-sitzungen.sql", "/test-positionen.sql", "/test-sync-requests.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class SyncRequestIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("inventur_test").withUsername("inventur_test")
            .withPassword(UUID.randomUUID().toString());

    private static final UUID USER = UUID.fromString("50000000-0000-4000-8000-000000000002");
    private static final UUID LIST = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID POSITION = UUID.fromString("84000000-0000-4000-8000-000000000001");
    private static final UUID REQUEST = UUID.fromString("87000000-0000-4000-8000-000000000001");
    private static final Instant TIME = Instant.parse("2026-10-14T08:00:00Z");

    @Autowired
    private SyncRequestsRepository requests;
    @Autowired
    private ErgebnisHistorieRepository historie;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;
    @PersistenceContext
    private EntityManager em;

    @Test
    void flywayV7AndHibernateValidateUseOnlyIsolatedPostgres() {
        assertEquals("inventur_test", jdbc.queryForObject("SELECT current_database()", String.class));
        assertEquals(9, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='7' AND success", Integer.class));
        assertTrue(em.getEntityManagerFactory().isOpen());
    }

    @Test
    @Transactional
    void requestCanBeStoredAndLoadedWithOptionalRoundReferencesAndFingerprint() throws Exception {
        UUID id = UUID.randomUUID();
        String body = "{\"zaehltag\":\"2026-10-14\"}";
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8)));
        requests.saveAndFlush(new SyncRequestEntity(id, em.find(BenutzerEntity.class, USER),
                SyncOperation.RUNDE_STARTEN, em.find(ZaehlerlisteEntity.class, LIST), null, null, null,
                1, null, null, null, body, fingerprint, 1, SyncRequestStatus.EINGEGANGEN,
                null, null, null, TIME, null));
        em.clear();
        var loaded = requests.findById(id).orElseThrow();
        assertEquals(id, loaded.getRequestId());
        assertEquals(USER, loaded.getUrheber().getId());
        assertEquals(LIST, loaded.getZaehlerliste().getId());
        assertEquals(SyncOperation.RUNDE_STARTEN, loaded.getOperation());
        assertNull(loaded.getZaehlsitzung());
        assertNull(loaded.getZaehlblatt());
        assertNull(loaded.getZaehlposition());
        assertEquals(1, loaded.getZuweisungVersion());
        assertNull(loaded.getErwarteteErgebnisrevision());
        assertEquals(body, loaded.getNormalisierterInhalt());
        assertEquals(fingerprint, loaded.getInhaltFingerprint());
        assertEquals(1, loaded.getInhaltFormatVersion());
        assertEquals(SyncRequestStatus.EINGEGANGEN, loaded.getStatus());
        assertEquals(TIME, loaded.getEingegangenAm());
        assertNull(loaded.getVerarbeitetAm());
    }

    @Test
    @Transactional
    void originalDecisionAndAuthorSurviveLaterListReassignment() {
        var request = requests.findById(REQUEST).orElseThrow();
        assertEquals(SyncRequestStatus.AKZEPTIERT, request.getStatus());
        assertEquals("OK", request.getEntscheidungscode());
        assertEquals(200, request.getAntwortHttpStatus());
        assertEquals("{\"revision\":1}", request.getAntwortJson());
        assertEquals(TIME.plusSeconds(1), request.getVerarbeitetAm());
        jdbc.update("UPDATE zaehlerliste SET aktueller_zaehler_id=NULL, zuweisung_version=2 WHERE id=?", LIST);
        em.clear();
        var loaded = requests.findById(REQUEST).orElseThrow();
        assertEquals(USER, loaded.getUrheber().getId());
        assertEquals(1, loaded.getZuweisungVersion());
        assertEquals(2, loaded.getZaehlerliste().getZuweisungVersion());
        assertEquals("{\"revision\":1}", loaded.getAntwortJson());
    }

    @Test
    void requestIdCannotBeInsertedTwice() throws Exception {
        assertRejected("INSERT INTO sync_request SELECT * FROM sync_request WHERE request_id='" + REQUEST + "'", "23505");
    }

    @Test
    @Transactional
    void allFiveOperationsAndRejectedDecisionCanBeStored() {
        for (var operation : SyncOperation.values()) {
            jdbc.update("UPDATE sync_request SET operation=? WHERE request_id=?", operation.name(), REQUEST);
        }
        jdbc.update("UPDATE sync_request SET status='ABGELEHNT', entscheidungscode='DEMO_KONFLIKT', "
                + "antwort_http_status=409, antwort_json='{\"code\":\"DEMO_KONFLIKT\"}' WHERE request_id=?", REQUEST);
        em.clear();
        var loaded = requests.findById(REQUEST).orElseThrow();
        assertEquals(SyncRequestStatus.ABGELEHNT, loaded.getStatus());
        assertEquals(409, loaded.getAntwortHttpStatus());
        assertEquals("DEMO_KONFLIKT", loaded.getEntscheidungscode());
    }

    @Test
    void invalidOperationsStatesVersionsAndIncompleteDecisionsAreRejected() throws Exception {
        for (String assignment : List.of("operation='UNGUELTIG'", "operation='SITZUNG_ABSCHLIESSEN'",
                "status='UNGUELTIG'", "zuweisung_version=-1", "erwartete_ergebnisrevision=-1",
                "erwartete_blatt_status_version=0", "erwartete_sitzung_status_version=0",
                "inhalt_fingerprint='kein-sha256'", "normalisierter_inhalt='  '", "inhalt_format_version=0",
                "antwort_http_status=600", "entscheidungscode=' '", "antwort_json=' '",
                "verarbeitet_am=NULL", "verarbeitet_am='2026-10-13T00:00:00Z'")) {
            assertRejected("UPDATE sync_request SET " + assignment + " WHERE request_id='" + REQUEST + "'", "23514");
        }
        assertRejected("UPDATE sync_request SET status='EINGEGANGEN' WHERE request_id='" + REQUEST + "'", "23514");
    }

    @Test
    void foreignKeysAndFullListSessionSheetPositionHierarchyAreEnforced() throws Exception {
        for (String assignment : List.of("urheber_id='90000000-0000-4000-8000-000000000001'",
                "zaehlerliste_id='90000000-0000-4000-8000-000000000001'",
                "zaehlerliste_id='10000000-0000-4000-8000-000000000002'",
                "zaehlsitzung_id='70000000-0000-4000-8000-000000000002'",
                "zaehlblatt_id='82000000-0000-4000-8000-000000000003'",
                "zaehlposition_id='84000000-0000-4000-8000-000000000003'")) {
            assertRejected("UPDATE sync_request SET " + assignment + " WHERE request_id='" + REQUEST + "'", "23503");
        }
        assertRejected("UPDATE sync_request SET zaehlsitzung_id=NULL WHERE request_id='" + REQUEST + "'", "23514");
        assertRejected("UPDATE sync_request SET zaehlblatt_id=NULL WHERE request_id='" + REQUEST + "'", "23514");
    }

    @Test
    @Transactional
    void historyPreservesNullZeroAndPositiveQuantitiesInRevisionOrder() {
        Integer[] quantities = {null, 0, 12};
        for (int i = 2; i >= 0; i--) {
            var request = requests.findById(UUID.fromString("87000000-0000-4000-8000-00000000000" + (i + 1))).orElseThrow();
            historie.save(new ErgebnisHistorieEntity(UUID.randomUUID(), em.find(ZaehlpositionEntity.class, POSITION),
                    request, i + 1, quantities[i], i == 0 ? "Demo: noch ungezaehlt" : null,
                    em.find(BenutzerEntity.class, USER), TIME.plusSeconds(i)));
        }
        historie.flush();
        em.clear();
        var rows = historie.findAllByZaehlposition_IdOrderByRevisionAsc(POSITION);
        assertEquals(3, rows.size());
        assertEquals(List.of(1L, 2L, 3L), rows.stream().map(ErgebnisHistorieEntity::getRevision).toList());
        assertNull(rows.get(0).getZaehlmenge());
        assertEquals("Demo: noch ungezaehlt", rows.get(0).getAnmerkung());
        assertEquals(0, rows.get(1).getZaehlmenge());
        assertEquals(12, rows.get(2).getZaehlmenge());
        assertEquals(REQUEST, rows.get(0).getRequest().getRequestId());
        assertEquals(USER, rows.get(0).getBenutzer().getId());
        assertEquals(TIME, rows.get(0).getGeaendertAm());
    }

    @Test
    void duplicateRevisionsAndDuplicateHistoryForSameRequestAreRejected() throws Exception {
        withHistory(connection -> {
            assertState(connection, historyInsert(UUID.randomUUID(), REQUEST, 2, "0", USER, POSITION), "23505");
        });
        withHistory(connection -> {
            UUID otherRequest = UUID.fromString("87000000-0000-4000-8000-000000000002");
            assertState(connection, historyInsert(UUID.randomUUID(), otherRequest, 1, "0", USER, POSITION), "23505");
        });
    }

    @Test
    void invalidHistoryQuantitiesRevisionsAndRequestContextAreRejected() throws Exception {
        assertRejected(historyInsert(UUID.randomUUID(), REQUEST, 1, "-1", USER, POSITION), "23514");
        assertRejected(historyInsert(UUID.randomUUID(), REQUEST, 0, "NULL", USER, POSITION), "23514");
        assertRejected(historyInsert(UUID.randomUUID(), UUID.randomUUID(), 1, "0", USER, POSITION), "23503");
        assertRejected(historyInsert(UUID.randomUUID(), REQUEST, 1, "0",
                UUID.fromString("50000000-0000-4000-8000-000000000001"), POSITION), "23503");
        assertRejected(historyInsert(UUID.randomUUID(), REQUEST, 1, "0", USER,
                UUID.fromString("84000000-0000-4000-8000-000000000002")), "23503");
    }

    @Test
    void historicalDataHasNoDeleteCascades() throws Exception {
        for (String sql : List.of("DELETE FROM sync_request WHERE request_id='" + REQUEST + "'",
                "DELETE FROM zaehlposition WHERE id='" + POSITION + "'",
                "DELETE FROM benutzer WHERE id='" + USER + "'")) {
            withHistory(connection -> assertState(connection, sql, "23503"));
        }
        assertEquals(3, requests.count());
    }

    @Test
    @Transactional
    void sheetStatusRequestReferenceIsSafeAndMustMatchSheet() throws Exception {
        jdbc.update("UPDATE zaehlblatt SET letzter_status_request_id=? WHERE id=?", REQUEST,
                UUID.fromString("82000000-0000-4000-8000-000000000001"));
        assertEquals(REQUEST, jdbc.queryForObject("SELECT letzter_status_request_id FROM zaehlblatt WHERE id=?",
                UUID.class, UUID.fromString("82000000-0000-4000-8000-000000000001")));
        assertRejected("UPDATE zaehlblatt SET letzter_status_request_id='" + REQUEST
                + "' WHERE id='82000000-0000-4000-8000-000000000002'", "23503");
    }

    @Test
    @Transactional
    void canonicalResultContentAndFingerprintFitUnchangedV7Schema() {
        var normalisierer = new de.pia.inventur.backend.service.sync.ErgebnisRequestNormalisierer();
        UUID sitzung = UUID.fromString("70000000-0000-4000-8000-000000000001");
        UUID blatt = UUID.fromString("82000000-0000-4000-8000-000000000001");
        var inhalt = new de.pia.inventur.backend.service.sync.ErgebnisSpeichernInhalt(
                1, SyncOperation.ERGEBNIS_SPEICHERN, LIST, sitzung, blatt, POSITION,
                1L, 0L, 1L, 1L, 1L, 12, "Demo: 12 Stueck");
        var normalisiert = normalisierer.normalisiere(inhalt);
        UUID id = UUID.randomUUID();
        requests.saveAndFlush(new SyncRequestEntity(id, em.find(BenutzerEntity.class, USER),
                inhalt.operation(), em.find(ZaehlerlisteEntity.class, LIST),
                em.find(ZaehlsitzungEntity.class, sitzung), em.find(ZaehlblattEntity.class, blatt),
                em.find(ZaehlpositionEntity.class, POSITION), inhalt.zuweisungVersion(),
                inhalt.basisRevision(), inhalt.erwarteteBlattStatusVersion(), inhalt.erwarteteSitzungStatusVersion(),
                normalisiert.normalisierterInhalt(), normalisiert.inhaltFingerprint(), normalisiert.inhaltFormatVersion(),
                SyncRequestStatus.EINGEGANGEN, null, null, null, TIME, null));
        em.clear();
        var loaded = requests.findById(id).orElseThrow();
        assertEquals(normalisiert.normalisierterInhalt(), loaded.getNormalisierterInhalt());
        assertEquals(normalisiert.inhaltFingerprint(), loaded.getInhaltFingerprint());
        assertEquals(1, loaded.getInhaltFormatVersion());
        assertTrue(loaded.getNormalisierterInhalt().contains("\"lokaleRevision\":1"));
    }

    private String historyInsert(UUID id, UUID request, long revision, String quantity, UUID user, UUID position) {
        return "INSERT INTO ergebnis_historie (id, zaehlposition_id, request_id, revision, zaehlmenge, benutzer_id, geaendert_am) VALUES ('"
                + id + "', '" + position + "', '" + request + "', " + revision + ", " + quantity + ", '" + user + "', '2026-10-14T08:00:00Z')";
    }

    @FunctionalInterface
    private interface SqlCheck { void run(java.sql.Connection connection) throws Exception; }

    private void withHistory(SqlCheck check) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate(historyInsert(UUID.randomUUID(), REQUEST, 1, "NULL", USER, POSITION));
                }
                check.run(connection);
            } finally {
                connection.rollback();
            }
        }
    }

    private void assertState(java.sql.Connection connection, String sql, String state) throws Exception {
        try (var statement = connection.createStatement()) {
            var error = assertThrows(SQLException.class, () -> statement.executeUpdate(sql));
            assertEquals(state, error.getSQLState());
        }
    }

    private void assertRejected(String sql, String state) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                assertState(connection, sql, state);
            } finally {
                connection.rollback();
            }
        }
    }
}
