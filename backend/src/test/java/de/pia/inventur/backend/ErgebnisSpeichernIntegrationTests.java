package de.pia.inventur.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import de.pia.inventur.backend.entity.SyncOperation;
import de.pia.inventur.backend.repository.ErgebnisSpeicherRepository;
import de.pia.inventur.backend.service.sync.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Sql(scripts = "/test-demo.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Sql(scripts = "/test-berechtigungspruefung.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(scripts = "/cleanup-ergebnis-speichern.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class ErgebnisSpeichernIntegrationTests {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("inventur_test").withUsername("inventur_test").withPassword(UUID.randomUUID().toString());
    private static final UUID USER = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID LIST = UUID.fromString("92000000-0000-4000-8000-000000000001");
    private static final UUID SESSION = UUID.fromString("93000000-0000-4000-8000-000000000001");
    private static final UUID SHEET = UUID.fromString("94000000-0000-4000-8000-000000000001");
    private static final UUID POSITION = UUID.fromString("96000000-0000-4000-8000-000000000001");
    @Autowired private ErgebnisSpeichernService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    @MockitoSpyBean private ErgebnisSpeicherRepository daten;
    private ErgebnisSpeicherRepository schreibzugriff;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void prepareFaultInjection() {
        // Den Spy hinter dem Transaktionsproxy konfigurieren, ohne MANDATORY aufzurufen.
        schreibzugriff = AopTestUtils.getUltimateTargetObject(daten);
    }

    @Test
    void firstWriteCommitsResultHistoryAndExactResponseWithServerIdentityAndTime() {
        assertEquals("inventur_test", jdbc.queryForObject("SELECT current_database()", String.class));
        UUID id = UUID.randomUUID();
        var result = save(id, basis());
        assertEquals("AKZEPTIERT", result.code());
        assertFalse(result.wiederholung());
        var response = mapper.readValue(result.entscheidung().antwortJson(), ErgebnisSpeichernAntwort.class);
        var json = mapper.readTree(result.entscheidung().antwortJson());
        assertEquals(8, json.size());
        assertTrue(json.has("anmerkung")); assertTrue(json.get("anmerkung").isNull());
        assertEquals(response.geaendertAm().toString(), json.get("geaendertAm").asString());
        assertEquals(id, response.requestId());
        assertEquals("AKZEPTIERT", response.entscheidung());
        assertEquals(POSITION, response.zaehlpositionId());
        assertEquals(7, response.bestaetigteLokaleRevision());
        assertEquals(1, response.ergebnisRevision());
        assertEquals(12, response.zaehlmenge());
        assertNull(response.anmerkung());
        assertEquals(200, result.entscheidung().antwortHttpStatus());
        assertEquals(response.geaendertAm(), result.entscheidung().verarbeitetAm());
        assertCounts(1, 1, 1);
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM zaehlergebnis e JOIN ergebnis_historie h
                ON h.zaehlposition_id=e.zaehlposition_id AND h.revision=e.revision
                JOIN sync_request r ON r.request_id=h.request_id
                WHERE e.zaehlposition_id=? AND e.zaehlmenge IS NOT DISTINCT FROM h.zaehlmenge
                AND e.anmerkung IS NOT DISTINCT FROM h.anmerkung AND e.geaendert_von=h.benutzer_id
                AND h.benutzer_id=r.urheber_id AND e.geaendert_am=h.geaendert_am
                AND h.geaendert_am=r.verarbeitet_am AND r.status='AKZEPTIERT'
                """, Integer.class, POSITION));
        assertEquals(USER, jdbc.queryForObject("SELECT geaendert_von FROM zaehlergebnis WHERE zaehlposition_id=?", UUID.class, POSITION));
        assertEquals(response.geaendertAm(), jdbc.queryForObject("SELECT geaendert_am FROM zaehlergebnis WHERE zaehlposition_id=?", OffsetDateTime.class, POSITION).toInstant());
        assertEquals(result.entscheidung().antwortJson(), jdbc.queryForObject("SELECT antwort_json FROM sync_request WHERE request_id=?", String.class, id));
    }

    @Test
    void nextWritePreservesResultIdAndPreviousHistory() {
        save(UUID.randomUUID(), basis());
        UUID resultId = jdbc.queryForObject("SELECT id FROM zaehlergebnis WHERE zaehlposition_id=?", UUID.class, POSITION);
        String previous = jdbc.queryForObject("SELECT row_to_json(h)::text FROM ergebnis_historie h WHERE zaehlposition_id=?", String.class, POSITION);
        var second = save(UUID.randomUUID(), mit("basisRevision", 1L));
        assertEquals(2, mapper.readValue(second.entscheidung().antwortJson(), ErgebnisSpeichernAntwort.class).ergebnisRevision());
        assertEquals(resultId, jdbc.queryForObject("SELECT id FROM zaehlergebnis WHERE zaehlposition_id=?", UUID.class, POSITION));
        assertEquals(previous, jdbc.queryForObject("SELECT row_to_json(h)::text FROM ergebnis_historie h WHERE zaehlposition_id=? AND revision=1", String.class, POSITION));
        assertCounts(1, 2, 2);
    }

    @Test
    void existingResultAtRevisionTwoIsUpdatedToThreeWithoutCreatingAnotherCurrentRow() {
        var fields = fields();
        fields.put("zaehlpositionId", UUID.fromString("96000000-0000-4000-8000-000000000002"));
        fields.put("basisRevision", 2L);
        var result = save(UUID.randomUUID(), mapper.convertValue(fields, ErgebnisSpeichernInhalt.class));
        assertEquals(3, mapper.readValue(result.entscheidung().antwortJson(), ErgebnisSpeichernAntwort.class).ergebnisRevision());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM zaehlergebnis", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ergebnis_historie WHERE revision=3", Integer.class));
    }

    @Test
    void businessRejectionDoesNotReserveIdAndCanBeRetriedWithValidContent() {
        UUID id = UUID.randomUUID();
        assertEquals("FALSCHE_ZUORDNUNG", save(id, mit("zaehlpositionId", UUID.fromString("96000000-0000-4000-8000-000000000003"))).code());
        assertCounts(0, 0, 0);
        assertEquals("AKZEPTIERT", save(id, basis()).code()); assertCounts(1, 1, 1);
    }

    static Stream<Arguments> quantities() { return Stream.of(Arguments.of((Integer) null, "Demo-Notiz"), Arguments.of(0, null), Arguments.of(23, "Demo-Notiz")); }
    @ParameterizedTest @MethodSource("quantities")
    void nullZeroPositiveAndIndependentNoteArePreserved(Integer quantity, String note) {
        var fields = fields(); fields.put("zaehlmenge", quantity); fields.put("anmerkung", note);
        var result = save(UUID.randomUUID(), mapper.convertValue(fields, ErgebnisSpeichernInhalt.class));
        var response = mapper.readValue(result.entscheidung().antwortJson(), ErgebnisSpeichernAntwort.class);
        assertEquals(quantity, response.zaehlmenge()); assertEquals(note, response.anmerkung());
        assertEquals(quantity, jdbc.queryForObject("SELECT zaehlmenge FROM zaehlergebnis WHERE zaehlposition_id=?", Integer.class, POSITION));
        assertEquals(note, jdbc.queryForObject("SELECT anmerkung FROM ergebnis_historie WHERE zaehlposition_id=?", String.class, POSITION));
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(Arguments.of("zaehlmenge", -1, "UNGUELTIGE_ZAEHLMENGE"),
                Arguments.of("basisRevision", 1L, "ERGEBNISREVISION_VERALTET"),
                Arguments.of("zuweisungVersion", 2L, "ZUWEISUNG_GEAENDERT"),
                Arguments.of("erwarteteBlattStatusVersion", 2L, "STATUSVERSION_VERALTET"),
                Arguments.of("zaehlpositionId", UUID.randomUUID(), "ZIEL_NICHT_VORHANDEN"),
                Arguments.of("zaehlpositionId", UUID.fromString("96000000-0000-4000-8000-000000000003"), "FALSCHE_ZUORDNUNG"));
    }
    @ParameterizedTest @MethodSource("invalidRequests")
    void invalidNewRequestsNeverChangeResultsAndOnlyTrustedConflictsAreArchived(String field, Object value, String code) {
        var result = save(UUID.randomUUID(), mit(field, value));
        assertEquals(code, result.code());
        boolean archived = KonfliktErfassungService.konfliktcode(ErgebnisPruefcode.valueOf(code)).isPresent();
        if (archived) { assertNotNull(result.entscheidung()); assertEquals(409, result.entscheidung().antwortHttpStatus()); }
        else assertNull(result.entscheidung());
        assertCounts(0, 0, archived ? 1 : 0);
    }

    @Test
    void inactiveWrongRoleAndUnassignedUsersCannotSave() {
        jdbc.update("UPDATE benutzer SET aktiv=false WHERE id=?", USER);
        assertEquals("BENUTZER_INAKTIV", save(UUID.randomUUID(), basis()).code());
        jdbc.update("UPDATE benutzer SET aktiv=true, rolle='ADMIN' WHERE id=?", USER);
        assertEquals("FALSCHE_ROLLE", save(UUID.randomUUID(), basis()).code());
        jdbc.update("UPDATE benutzer SET rolle='ZAEHLER' WHERE id=?", USER);
        assertEquals("LISTE_NICHT_ZUGEWIESEN", service.speichere(UUID.randomUUID(),
                new GepruefterUrheber(UUID.fromString("50000000-0000-4000-8000-000000000002")), basis()).code());
        assertCounts(0, 0, 0);
    }

    @Test
    void closedSheetAndSessionCannotSave() {
        jdbc.update("UPDATE zaehlblatt SET status='ABGESCHLOSSEN' WHERE id=?", SHEET);
        assertEquals("BLATT_ABGESCHLOSSEN", save(UUID.randomUUID(), basis()).code());
        jdbc.update("UPDATE zaehlblatt SET status='OFFEN' WHERE id=?", SHEET);
        jdbc.update("UPDATE zaehlsitzung SET status='ABGESCHLOSSEN' WHERE id=?", SESSION);
        assertEquals("SITZUNG_ABGESCHLOSSEN", save(UUID.randomUUID(), basis()).code()); assertCounts(0, 0, 2);
    }

    @Test
    void lostResponseReplaysExactBytesEvenAfterAssignmentAndClosureChange() {
        UUID id = UUID.randomUUID(); var first = save(id, basis());
        jdbc.update("UPDATE zaehlerliste SET aktueller_zaehler_id=NULL, zuweisung_version=2 WHERE id=?", LIST);
        jdbc.update("UPDATE zaehlblatt SET status='ABGESCHLOSSEN', status_version=2 WHERE id=?", SHEET);
        var second = save(id, basis());
        assertTrue(second.wiederholung()); assertEquals(first.entscheidung(), second.entscheidung()); assertCounts(1, 1, 1);
    }

    @Test
    void reusedIdWithChangedContentOrForeignIdentityCannotOverwriteOrDiscloseResponse() {
        UUID id = UUID.randomUUID(); save(id, basis());
        assertEquals("REQUEST_ID_REUSED", save(id, mit("zaehlmenge", 13)).code());
        var foreign = service.speichere(id, new GepruefterUrheber(UUID.fromString("50000000-0000-4000-8000-000000000002")), basis());
        assertEquals("NICHT_BERECHTIGTER_ZUGRIFF", foreign.code()); assertNull(foreign.entscheidung()); assertCounts(1, 1, 1);
    }

    @Test
    void identicalConcurrentRequestsReturnOneDecisionAndOneHistory() throws Exception { concurrentRequests(true); }
    @Test
    void differentConcurrentRequestsOnSameBaseProduceOnlyOneAcceptedRevision() throws Exception { concurrentRequests(false); }

    private void concurrentRequests(boolean identical) throws Exception {
        var reached = new CountDownLatch(1); var release = new CountDownLatch(1);
        pauseBeforeHistory(reached, release);
        UUID firstId = UUID.randomUUID(); UUID secondId = identical ? firstId : UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> save(firstId, basis()));
            try {
                assertTrue(reached.await(10, TimeUnit.SECONDS)); assertFalse(first.isDone(), "Noch kein Commit, daher keine Erfolgsantwort");
                var second = executor.submit(() -> save(secondId, basis()));
                waiting(second); release.countDown();
                var accepted = first.get(10, TimeUnit.SECONDS); var result = second.get(10, TimeUnit.SECONDS);
                assertEquals("AKZEPTIERT", accepted.code());
                if (identical) { assertTrue(result.wiederholung()); assertEquals(accepted.entscheidung(), result.entscheidung()); }
                else { assertEquals("ERGEBNISREVISION_VERALTET", result.code()); assertNotNull(result.entscheidung()); }
                assertCounts(1, 1, identical ? 1 : 2);
            } finally { release.countDown(); }
        }
    }

    static Stream<Arguments> mutations() {
        return Stream.of(Arguments.of("UPDATE zaehlerliste SET aktueller_zaehler_id=NULL, zuweisung_version=2 WHERE id='" + LIST + "'", "LISTE_NICHT_ZUGEWIESEN"),
                Arguments.of("UPDATE zaehlsitzung SET status='ABGESCHLOSSEN', status_version=2 WHERE id='" + SESSION + "'", "SITZUNG_ABGESCHLOSSEN"),
                Arguments.of("UPDATE zaehlblatt SET status='ABGESCHLOSSEN', status_version=2 WHERE id='" + SHEET + "'", "BLATT_ABGESCHLOSSEN"));
    }
    @ParameterizedTest @MethodSource("mutations")
    void saveLocksBlockConcurrentAssignmentOrClosureUntilCommit(String sql, String code) throws Exception {
        var reached = new CountDownLatch(1); var release = new CountDownLatch(1); pauseBeforeHistory(reached, release);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var writer = executor.submit(() -> save(UUID.randomUUID(), basis()));
            try {
                assertTrue(reached.await(10, TimeUnit.SECONDS));
                var mutation = executor.submit(() -> new TransactionTemplate(manager).execute(tx -> jdbc.update(sql)));
                waiting(mutation); release.countDown();
                assertEquals("AKZEPTIERT", writer.get(10, TimeUnit.SECONDS).code()); assertEquals(1, mutation.get(10, TimeUnit.SECONDS));
                assertEquals(code, save(UUID.randomUUID(), mit("basisRevision", 1L)).code());
                assertCounts(1, 1, "LISTE_NICHT_ZUGEWIESEN".equals(code) ? 1 : 2);
            } finally { release.countDown(); }
        }
    }

    @ParameterizedTest @MethodSource("mutations")
    void mutationCommittedFirstMakesWaitingSaveRejectWithoutPartialWrites(String sql, String code) throws Exception {
        var reached = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var mutation = executor.submit(() -> new TransactionTemplate(manager).execute(tx -> {
                jdbc.update(sql); reached.countDown(); await(release); return true;
            }));
            try {
                assertTrue(reached.await(10, TimeUnit.SECONDS));
                var writer = executor.submit(() -> save(UUID.randomUUID(), basis())); waiting(writer); release.countDown();
                assertTrue(mutation.get(10, TimeUnit.SECONDS)); assertEquals(code, writer.get(10, TimeUnit.SECONDS).code());
                assertCounts(0, 0, "LISTE_NICHT_ZUGEWIESEN".equals(code) ? 0 : 1);
            } finally { release.countDown(); }
        }
    }

    @Test
    void historyFailureRollsBackRegistrationAndFirstResultThenSameIdCanRetry() {
        UUID id = UUID.randomUUID();
        doThrow(new IllegalStateException("Simulierter Historienfehler")).when(schreibzugriff).ergaenzeHistorie(any(), any());
        var failure = assertThrows(InvalidDataAccessApiUsageException.class, () -> save(id, basis()));
        assertInstanceOf(IllegalStateException.class, failure.getCause()); assertCounts(0, 0, 0);
        reset(schreibzugriff); assertEquals("AKZEPTIERT", save(id, basis()).code()); assertCounts(1, 1, 1);
    }

    @Test
    void finalDecisionFailureRollsBackEvenAlreadyWrittenHistoryAndExistingResultUpdate() {
        save(UUID.randomUUID(), basis());
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("Simulierter Entscheidungsfehler"); })
                .when(schreibzugriff).akzeptiereRequest(any(), anyString());
        var failure = assertThrows(InvalidDataAccessApiUsageException.class, () -> save(UUID.randomUUID(), mit("basisRevision", 1L)));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertCounts(1, 1, 1);
        assertEquals(1L, jdbc.queryForObject("SELECT revision FROM zaehlergebnis WHERE zaehlposition_id=?", Long.class, POSITION));
    }

    @Test
    void commitGuardFailureNeverReturnsSuccessAndRollsBackAllRows() {
        doNothing().when(schreibzugriff).akzeptiereRequest(any(), anyString());
        assertThrows(IllegalStateException.class, () -> save(UUID.randomUUID(), basis())); assertCounts(0, 0, 0);
    }

    @Test
    void ambientTransactionIsRejectedSoSuccessCanNeverPrecedeCommit() {
        assertThrows(IllegalTransactionStateException.class, () -> new TransactionTemplate(manager)
                .execute(tx -> save(UUID.randomUUID(), basis()))); assertCounts(0, 0, 0);
    }

    private void pauseBeforeHistory(CountDownLatch reached, CountDownLatch release) {
        doAnswer(call -> { reached.countDown(); await(release); return call.callRealMethod(); })
                .when(schreibzugriff).ergaenzeHistorie(any(), any());
    }
    private void waiting(Future<?> task) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); boolean locked = false;
        while (System.nanoTime() < deadline) {
            if (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'", Integer.class) > 0) { locked = true; break; }
            Thread.sleep(20);
        }
        assertTrue(locked, "PostgreSQL muss an der konkurrierenden Sperre warten"); assertFalse(task.isDone());
    }
    private void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test-Timeout"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
    }
    private void assertCounts(int current, int history, int requests) {
        assertEquals(current, jdbc.queryForObject("SELECT count(*) FROM zaehlergebnis WHERE zaehlposition_id=?", Integer.class, POSITION));
        assertEquals(history, jdbc.queryForObject("SELECT count(*) FROM ergebnis_historie", Integer.class));
        assertEquals(requests, jdbc.queryForObject("SELECT count(*) FROM sync_request", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM zaehlergebnis WHERE zaehlposition_id='96000000-0000-4000-8000-000000000002'", Integer.class));
    }
    private ErgebnisSpeicherergebnis save(UUID id, ErgebnisSpeichernInhalt content) { return service.speichere(id, new GepruefterUrheber(USER), content); }
    private ErgebnisSpeichernInhalt basis() {
        return new ErgebnisSpeichernInhalt(1, SyncOperation.ERGEBNIS_SPEICHERN, LIST, SESSION, SHEET, POSITION, 1L, 0L, 7L, 1L, 1L, 12, null);
    }
    private LinkedHashMap<String, Object> fields() { return mapper.convertValue(basis(), new TypeReference<LinkedHashMap<String, Object>>() {}); }
    private ErgebnisSpeichernInhalt mit(String field, Object value) {
        var fields = fields(); fields.put(field, value); return mapper.convertValue(fields, ErgebnisSpeichernInhalt.class);
    }
}
