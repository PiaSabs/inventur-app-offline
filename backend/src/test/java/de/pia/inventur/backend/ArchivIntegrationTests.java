package de.pia.inventur.backend;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import de.pia.inventur.backend.entity.*;
import de.pia.inventur.backend.repository.*;
import de.pia.inventur.backend.service.sync.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.stream.Stream;
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
@Transactional
@Sql(scripts = {"/test-demo.sql", "/test-berechtigungspruefung.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class ArchivIntegrationTests {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("inventur_test").withUsername("inventur_test").withPassword(UUID.randomUUID().toString());

    private static final UUID USER = id("50000000-0000-4000-8000-000000000001");
    private static final UUID OTHER = id("50000000-0000-4000-8000-000000000002");
    private static final UUID ADMIN = id("50000000-0000-4000-8000-000000000003");
    private static final UUID LIST = id("92000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_LIST = id("92000000-0000-4000-8000-000000000002");
    private static final UUID SESSION = id("93000000-0000-4000-8000-000000000001");
    private static final UUID SHEET = id("94000000-0000-4000-8000-000000000001");
    private static final UUID POSITION = id("96000000-0000-4000-8000-000000000001");

    @Autowired ZuweisungHistorieRepository historie;
    @Autowired SyncKonflikteRepository konflikte;
    @Autowired SyncRequestsRepository requests;
    @Autowired ErgebnisRequestNormalisierer normalisierer;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @PersistenceContext EntityManager em;

    @Test
    void v9AndHibernateValidateOnlyIsolatedPostgresWithElevenBusinessTables() {
        assertEquals("inventur_test", jdbc.queryForObject("SELECT current_database()", String.class));
        assertEquals(9, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='9' AND success", Integer.class));
        assertEquals(11, jdbc.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename <> 'flyway_schema_history'", Integer.class));
        assertTrue(em.getEntityManagerFactory().isOpen());
        assertEquals(0, historie.count()); assertEquals(0, konflikte.count());
    }

    static Stream<Arguments> assignments() {
        return Stream.of(Arguments.of(null, USER), Arguments.of(USER, OTHER), Arguments.of(OTHER, null));
    }
    @ParameterizedTest @MethodSource("assignments")
    void assignmentAndReleaseCanBeStoredWithNullableCounters(UUID oldUser, UUID newUser) {
        var before = serverNow();
        var saved = historie.saveAndFlush(history(oldUser, newUser, 0L, 1L, null));
        em.clear();
        var loaded = historie.findById(saved.getId()).orElseThrow();
        assertEquals(oldUser, loaded.getVorherigerZaehler() == null ? null : loaded.getVorherigerZaehler().getId());
        assertEquals(newUser, loaded.getNeuerZaehler() == null ? null : loaded.getNeuerZaehler().getId());
        assertEquals(LIST, loaded.getZaehlerliste().getId()); assertEquals(ADMIN, loaded.getVerantwortlicherBenutzer().getId());
        assertEquals(0L, loaded.getVersionVorher()); assertEquals(1L, loaded.getVersionNachher()); assertNull(loaded.getSyncRequest());
        assertFalse(loaded.getGeaendertAm().isBefore(before)); assertFalse(loaded.getGeaendertAm().isAfter(serverNow()));
    }

    @Test
    void laterAssignmentPreservesHistoryConflictAndExistingResults() {
        var first = historie.saveAndFlush(history(null, USER, 0L, 1L, null));
        var second = historie.saveAndFlush(history(USER, OTHER, 1L, 2L, null));
        var conflict = konflikte.saveAndFlush(conflict(UUID.randomUUID(), USER, 0, "Demo: Stück 🟩\r\n", SyncKonfliktCode.ZUWEISUNG_GEAENDERT, true));
        jdbc.update("UPDATE zaehlerliste SET aktueller_zaehler_id=?,zuweisung_version=2 WHERE id=?", OTHER, LIST);
        jdbc.update("UPDATE benutzer SET rolle='ADMIN',aktiv=false WHERE id=?", USER);
        em.clear();
        var loaded = historie.findAllByZaehlerliste_IdOrderByVersionNachherAsc(LIST);
        assertEquals(List.of(first.getId(), second.getId()), loaded.stream().map(ZuweisungHistorieEntity::getId).toList());
        assertEquals(USER, loaded.get(0).getNeuerZaehler().getId());
        assertEquals(USER, loaded.get(1).getVorherigerZaehler().getId());
        var oldConflict = konflikte.findById(conflict.getId()).orElseThrow();
        assertEquals(USER, oldConflict.getUrheber().getId()); assertEquals(LIST, oldConflict.getZaehlsitzung().getZaehlerliste().getId());
        assertEquals(1L, oldConflict.getZuweisungVersion()); assertEquals(0, oldConflict.getZaehlmenge());
        assertEquals(2L, jdbc.queryForObject("SELECT revision FROM zaehlergebnis WHERE zaehlposition_id='96000000-0000-4000-8000-000000000002'", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM zaehlergebnis", Integer.class));
    }

    @Test
    void optionalHistoryRequestMustBelongToSameList() {
        var request = requestEntity();
        requests.saveAndFlush(request);
        var history = historie.saveAndFlush(history(null, USER, 0L, 1L, request));
        em.clear();
        assertEquals(request.getRequestId(), historie.findById(history.getId()).orElseThrow().getSyncRequest().getRequestId());
    }

    @Test
    void historyRequestForAnotherListIsRejected() {
        var request = requests.saveAndFlush(requestEntity());
        var row = new ZuweisungHistorieEntity(UUID.randomUUID(), ref(ZaehlerlisteEntity.class, OTHER_LIST),
                null, ref(BenutzerEntity.class, USER), 0L, 1L, ref(BenutzerEntity.class, ADMIN), request);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> historie.saveAndFlush(row));
    }

    @Test
    void duplicateHistoryVersionIsRejected() throws Exception {
        rejected("zuweisung_historie", historyRow(), historyRow(), "23505");
    }

    static Stream<Arguments> invalidHistory() {
        return Stream.of(Arguments.of("version_vorher", -1L, "23514"),
                Arguments.of("version_nachher", 0L, "23514"), Arguments.of("version_nachher", 2L, "23514"),
                Arguments.of("zaehlerliste_id", UUID.randomUUID(), "23503"),
                Arguments.of("vorheriger_zaehler_id", UUID.randomUUID(), "23503"),
                Arguments.of("neuer_zaehler_id", UUID.randomUUID(), "23503"),
                Arguments.of("verantwortlicher_benutzer_id", UUID.randomUUID(), "23503"),
                Arguments.of("sync_request_id", UUID.randomUUID(), "23503"),
                Arguments.of("neuer_zaehler_id", null, "23514"));
    }
    @ParameterizedTest @MethodSource("invalidHistory")
    void historyConstraintsRejectInvalidVersionsUsersOrRequests(String key, Object value, String state) throws Exception {
        var row = historyRow(); row.put(key, value); rejected("zuweisung_historie", null, row, state);
    }

    @Test
    void noOpAndOverflowingVersionStepAreRejected() throws Exception {
        var row = historyRow(); row.put("vorheriger_zaehler_id", USER);
        rejected("zuweisung_historie", null, row, "23514");
        row = historyRow(); row.put("version_vorher", Long.MAX_VALUE); row.put("version_nachher", Long.MAX_VALUE);
        rejected("zuweisung_historie", null, row, "23514");
    }

    static Stream<Arguments> quantities() {
        return Stream.of(Arguments.of((Integer)null, "Demo ohne Menge"), Arguments.of(0, null),
                Arguments.of(8, "Demo: Stück 🟩\r\n"));
    }
    @ParameterizedTest @MethodSource("quantities")
    void originalConflictPayloadAndNullableQuantityArePreservedWithoutSyncRequest(Integer quantity, String note) {
        var before = serverNow(); var requestId = UUID.randomUUID();
        var saved = konflikte.saveAndFlush(conflict(requestId, USER, quantity, note, SyncKonfliktCode.ERGEBNISREVISION_VERALTET, true));
        var content = saved.getNormalisierterInhalt(); var fingerprint = saved.getInhaltFingerprint();
        em.clear();
        var loaded = konflikte.findByUrheber_IdAndUrspruenglicheRequestIdAndInhaltFormatVersionAndInhaltFingerprint(
                USER, requestId, 1, fingerprint).orElseThrow();
        assertEquals(content, loaded.getNormalisierterInhalt()); assertEquals(fingerprint, loaded.getInhaltFingerprint());
        assertEquals(quantity, loaded.getZaehlmenge()); assertEquals(note, loaded.getAnmerkung());
        assertEquals(3L, loaded.getBasisRevision()); assertEquals(7L, loaded.getLokaleRevision());
        assertEquals(1L, loaded.getZuweisungVersion()); assertEquals(1L, loaded.getErwarteteBlattStatusVersion());
        assertEquals(1L, loaded.getErwarteteSitzungStatusVersion()); assertEquals(1, loaded.getInhaltFormatVersion());
        assertEquals(POSITION, loaded.getZaehlposition().getId()); assertEquals(SHEET, loaded.getZaehlblatt().getId());
        assertEquals(SyncKonfliktStatus.OFFEN, loaded.getStatus()); assertFalse(loaded.getErfasstAm().isBefore(before));
        assertEquals(0, requests.count());
        assertEquals(1, konflikte.findAllByZaehlerliste_IdAndStatusOrderByErfasstAmAscIdAsc(LIST, SyncKonfliktStatus.OFFEN).size());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM zaehlergebnis", Integer.class));
    }

    @ParameterizedTest @EnumSource(SyncKonfliktCode.class)
    void definedConflictReasonsCanBeLoaded(SyncKonfliktCode code) {
        var saved = konflikte.saveAndFlush(conflict(UUID.randomUUID(), USER, 0, null, code, true));
        em.clear(); assertEquals(code, konflikte.findById(saved.getId()).orElseThrow().getKonfliktcode());
    }

    @ParameterizedTest @EnumSource(SyncKonfliktStatus.class)
    void definedProcessingStatusesCanBeLoaded(SyncKonfliktStatus status) {
        var saved = konflikte.saveAndFlush(conflict(UUID.randomUUID(), USER, 0, null, SyncKonfliktCode.ZUWEISUNG_GEAENDERT, true));
        // Nur Testfixture; noch kein fachlicher Bearbeitungsprozess.
        jdbc.update("UPDATE sync_konflikt SET status=? WHERE id=?", status.name(), saved.getId());
        em.clear();
        assertEquals(status, konflikte.findById(saved.getId()).orElseThrow().getStatus());
    }

    @Test
    void unknownAndInconsistentClaimedTargetsRemainOnlyInOriginalPayload() {
        UUID unknown = UUID.randomUUID();
        var body = new ErgebnisSpeichernInhalt(1, SyncOperation.ERGEBNIS_SPEICHERN, unknown, SESSION, SHEET, POSITION,
                1L, 3L, 7L, 1L, 1L, 0, "Demo unbekanntes Ziel");
        var canonical = normalisierer.normalisiere(body);
        var entity = new SyncKonfliktEntity(UUID.randomUUID(), UUID.randomUUID(), ref(BenutzerEntity.class, USER),
                null, null, null, null, SyncKonfliktCode.FALSCHE_ZUORDNUNG, canonical.normalisierterInhalt(), 1,
                canonical.inhaltFingerprint(), 3L, 7L, 1L, 1L, 1L, 0, body.anmerkung(), SyncKonfliktStatus.OFFEN);
        var saved = konflikte.saveAndFlush(entity); em.clear();
        var loaded = konflikte.findById(saved.getId()).orElseThrow();
        assertEquals(canonical.normalisierterInhalt(), loaded.getNormalisierterInhalt());
        assertTrue(loaded.getNormalisierterInhalt().contains(unknown.toString()));
        assertNull(loaded.getZaehlerliste()); assertNull(loaded.getZaehlsitzung());
        assertNull(loaded.getZaehlblatt()); assertNull(loaded.getZaehlposition());
    }

    @Test
    void identicalConflictCannotBeInsertedTwiceEvenWithAnotherReasonOrStatus() throws Exception {
        var first = conflictRow(); var second = new LinkedHashMap<>(first); second.put("id", UUID.randomUUID());
        second.put("konfliktcode", "BLATT_ABGESCHLOSSEN"); second.put("status", "GELOEST");
        rejected("sync_konflikt", first, second, "23505");
    }

    @Test
    void sameRequestIdWithDifferentContentOrAnotherUserIsNotMerged() {
        var requestId = UUID.randomUUID();
        konflikte.saveAndFlush(conflict(requestId, USER, 0, null, SyncKonfliktCode.REQUEST_ID_REUSED, true));
        konflikte.saveAndFlush(conflict(requestId, USER, 1, null, SyncKonfliktCode.REQUEST_ID_REUSED, true));
        konflikte.saveAndFlush(conflict(requestId, OTHER, 0, null, SyncKonfliktCode.REQUEST_ID_REUSED, true));
        assertEquals(3, konflikte.count());
    }

    static Stream<Arguments> invalidConflict() {
        return Stream.of(Arguments.of("urheber_id", UUID.randomUUID(), "23503"),
                Arguments.of("zaehlerliste_id", UUID.randomUUID(), "23503"),
                Arguments.of("zaehlsitzung_id", id("93000000-0000-4000-8000-000000000002"), "23503"),
                Arguments.of("zaehlblatt_id", id("94000000-0000-4000-8000-000000000002"), "23503"),
                Arguments.of("zaehlposition_id", id("96000000-0000-4000-8000-000000000003"), "23503"),
                Arguments.of("zaehlblatt_id", null, "23514"),
                Arguments.of("basis_revision", -1L, "23514"), Arguments.of("lokale_revision", -1L, "23514"),
                Arguments.of("zuweisung_version", -1L, "23514"), Arguments.of("erwartete_blatt_status_version", 0L, "23514"),
                Arguments.of("erwartete_sitzung_status_version", 0L, "23514"), Arguments.of("zaehlmenge", -1, "23514"),
                Arguments.of("inhalt_format_version", 0, "23514"), Arguments.of("inhalt_fingerprint", "0".repeat(64), "23514"),
                Arguments.of("normalisierter_inhalt", "Demo manipuliert", "23514"),
                Arguments.of("status", "AUTOMATISCH_UEBERSCHRIEBEN", "23514"),
                Arguments.of("konfliktcode", "UNBEKANNT", "23514"));
    }
    @ParameterizedTest @MethodSource("invalidConflict")
    void conflictConstraintsRejectForeignHierarchyInvalidSnapshotsAndFingerprint(String key, Object value, String state) throws Exception {
        var row = conflictRow(); row.put(key, value); rejected("sync_konflikt", null, row, state);
    }

    @Test
    void archivesHaveNoCascadingOrSetNullForeignKeysAndBlockDeletionOfReferencedData() throws Exception {
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid IN ('zuweisung_historie'::regclass,'sync_konflikt'::regclass) AND contype='f' AND confdeltype <> 'a'", Integer.class));
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                insert(connection, "zuweisung_historie", historyRow());
                insert(connection, "sync_konflikt", conflictRow());
                try (var statement = connection.prepareStatement("DELETE FROM benutzer WHERE id=?")) {
                    statement.setObject(1, ADMIN); var error = assertThrows(SQLException.class, statement::executeUpdate);
                    assertEquals("23503", error.getSQLState());
                }
            } finally { connection.rollback(); }
        }
    }

    private ZuweisungHistorieEntity history(UUID oldUser, UUID newUser, Long before, Long after, SyncRequestEntity request) {
        return new ZuweisungHistorieEntity(UUID.randomUUID(), ref(ZaehlerlisteEntity.class, LIST),
                oldUser == null ? null : ref(BenutzerEntity.class, oldUser),
                newUser == null ? null : ref(BenutzerEntity.class, newUser), before, after,
                ref(BenutzerEntity.class, ADMIN), request);
    }

    private SyncKonfliktEntity conflict(UUID requestId, UUID user, Integer quantity, String note, SyncKonfliktCode code, boolean resolved) {
        var content = normalisierer.normalisiere(new ErgebnisSpeichernInhalt(1, SyncOperation.ERGEBNIS_SPEICHERN,
                LIST, SESSION, SHEET, POSITION, 1L, 3L, 7L, 1L, 1L, quantity, note));
        return new SyncKonfliktEntity(UUID.randomUUID(), requestId, ref(BenutzerEntity.class, user),
                resolved ? ref(ZaehlerlisteEntity.class, LIST) : null, resolved ? ref(ZaehlsitzungEntity.class, SESSION) : null,
                resolved ? ref(ZaehlblattEntity.class, SHEET) : null, resolved ? ref(ZaehlpositionEntity.class, POSITION) : null,
                code, content.normalisierterInhalt(), content.inhaltFormatVersion(), content.inhaltFingerprint(),
                3L, 7L, 1L, 1L, 1L, quantity, note, SyncKonfliktStatus.OFFEN);
    }

    private SyncRequestEntity requestEntity() {
        // Opaquer fiktiver Zuweisungsinhalt fuer den FK-Test, kein neuer REST-/Normalisierungsvertrag.
        String content = "{\"demo\":\"Zuweisung\"}";
        String fingerprint = jdbc.queryForObject("SELECT encode(sha256(convert_to(?, 'UTF8')), 'hex')", String.class, content);
        return new SyncRequestEntity(UUID.randomUUID(), ref(BenutzerEntity.class, ADMIN), SyncOperation.LISTE_ZUWEISEN,
                ref(ZaehlerlisteEntity.class, LIST), null, null, null, 1L, null, null, null,
                content, fingerprint, 1, SyncRequestStatus.EINGEGANGEN,
                null, null, null, Instant.now(), null);
    }

    private Map<String, Object> historyRow() {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", UUID.randomUUID()); row.put("zaehlerliste_id", LIST); row.put("vorheriger_zaehler_id", null);
        row.put("neuer_zaehler_id", USER); row.put("version_vorher", 0L); row.put("version_nachher", 1L);
        row.put("verantwortlicher_benutzer_id", ADMIN); return row;
    }

    private Map<String, Object> conflictRow() {
        var row = new LinkedHashMap<String, Object>(); var canonical = normalisierer.normalisiere(
                new ErgebnisSpeichernInhalt(1, SyncOperation.ERGEBNIS_SPEICHERN, LIST, SESSION, SHEET, POSITION, 1L, 3L, 7L, 1L, 1L, 0, "Demo"));
        row.put("id", UUID.randomUUID()); row.put("urspruengliche_request_id", UUID.randomUUID()); row.put("urheber_id", USER);
        row.put("zaehlerliste_id", LIST); row.put("zaehlsitzung_id", SESSION); row.put("zaehlblatt_id", SHEET); row.put("zaehlposition_id", POSITION);
        row.put("konfliktcode", "ZUWEISUNG_GEAENDERT"); row.put("normalisierter_inhalt", canonical.normalisierterInhalt());
        row.put("inhalt_format_version", 1); row.put("inhalt_fingerprint", canonical.inhaltFingerprint());
        row.put("basis_revision", 3L); row.put("lokale_revision", 7L); row.put("zuweisung_version", 1L);
        row.put("erwartete_blatt_status_version", 1L); row.put("erwartete_sitzung_status_version", 1L);
        row.put("zaehlmenge", 0); row.put("anmerkung", "Demo"); row.put("status", "OFFEN"); return row;
    }

    private void rejected(String table, Map<String, Object> first, Map<String, Object> second, String state) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (first != null) insert(connection, table, first);
                var error = assertThrows(SQLException.class, () -> insert(connection, table, second));
                assertEquals(state, error.getSQLState());
            } finally { connection.rollback(); }
        }
    }

    private void insert(Connection connection, String table, Map<String, Object> row) throws SQLException {
        String placeholders = String.join(",", java.util.Collections.nCopies(row.size(), "?"));
        try (var statement = connection.prepareStatement("INSERT INTO " + table + " (" + String.join(",", row.keySet()) + ") VALUES (" + placeholders + ")")) {
            int index = 1; for (var value : row.values()) statement.setObject(index++, value); statement.executeUpdate();
        }
    }
    private <T> T ref(Class<T> type, UUID id) { return em.getReference(type, id); }
    private Instant serverNow() {
        // DB-generierte Zeitpunkte gegen dieselbe Uhr/Genauigkeit pruefen, nicht die Windows-Uhr.
        return jdbc.queryForObject("SELECT clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant());
    }
    private static UUID id(String value) { return UUID.fromString(value); }
}

