package de.pia.inventur.backend;

import static org.junit.jupiter.api.Assertions.*;
import static de.pia.inventur.backend.service.sync.ErgebnisPruefcode.*;

import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import de.pia.inventur.backend.entity.*;
import de.pia.inventur.backend.service.sync.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Transactional;
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
@Sql(scripts = "/cleanup-berechtigungspruefung.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class ErgebnisBerechtigungIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("inventur_test").withUsername("inventur_test")
            .withPassword(UUID.randomUUID().toString());

    private static final UUID USER = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_USER = UUID.fromString("50000000-0000-4000-8000-000000000002");
    private static final UUID LIST = UUID.fromString("92000000-0000-4000-8000-000000000001");
    private static final UUID SESSION = UUID.fromString("93000000-0000-4000-8000-000000000001");
    private static final UUID SHEET = UUID.fromString("94000000-0000-4000-8000-000000000001");
    private static final UUID POSITION = UUID.fromString("96000000-0000-4000-8000-000000000001");
    private static final UUID EXISTING = UUID.fromString("96000000-0000-4000-8000-000000000002");

    @Autowired
    private ErgebnisBerechtigungsPruefer pruefer;
    @Autowired
    private RequestRegistrierungsService registrierung;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @PersistenceContext
    private EntityManager em;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void validNullZeroAndPositiveRequestsAreAllowedWithoutAnyWrites() {
        assertEquals("inventur_test", jdbc.queryForObject("SELECT current_database()", String.class));
        for (Integer quantity : new Integer[] {null, 0, 12}) {
            assertEquals(ZULAESSIG, check(mit("zaehlmenge", quantity)).code());
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM zaehlergebnis", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sync_request", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ergebnis_historie", Integer.class));
    }

    @Test
    @Transactional
    void inactiveUserWrongRoleAndMissingUserAreRejected() {
        jdbc.update("UPDATE benutzer SET aktiv=false WHERE id=?", USER);
        assertEquals(BENUTZER_INAKTIV, check(basis()).code());
        jdbc.update("UPDATE benutzer SET aktiv=true, rolle='ADMIN' WHERE id=?", USER);
        assertEquals(FALSCHE_ROLLE, check(basis()).code());
        assertEquals(BENUTZER_NICHT_VORHANDEN,
                pruefer.pruefe(new GepruefterUrheber(UUID.randomUUID()), basis()).code());
    }

    @Test
    @Transactional
    void foreignUnassignedAndChangedAssignmentsAreRejected() {
        assertEquals(LISTE_NICHT_ZUGEWIESEN,
                check(mit("zaehlerlisteId", UUID.fromString("92000000-0000-4000-8000-000000000002"))).code());
        jdbc.update("UPDATE zaehlerliste SET aktueller_zaehler_id=NULL WHERE id=?", LIST);
        assertEquals(LISTE_NICHT_ZUGEWIESEN, check(basis()).code());
        jdbc.update("UPDATE zaehlerliste SET aktueller_zaehler_id=?, zuweisung_version=2 WHERE id=?", USER, LIST);
        assertEquals(ZUWEISUNG_GEAENDERT, check(basis()).code());
    }

    @Test
    void eachWrongSessionSheetOrPositionParentIsRejected() {
        assertEquals(FALSCHE_ZUORDNUNG,
                check(mit("zaehlsitzungId", UUID.fromString("93000000-0000-4000-8000-000000000002"))).code());
        assertEquals(FALSCHE_ZUORDNUNG,
                check(mit("zaehlblattId", UUID.fromString("94000000-0000-4000-8000-000000000002"))).code());
        assertEquals(FALSCHE_ZUORDNUNG,
                check(mit("zaehlpositionId", UUID.fromString("96000000-0000-4000-8000-000000000003"))).code());
    }

    @Test
    @Transactional
    void closedSessionAndSheetAreRejected() {
        jdbc.update("UPDATE zaehlsitzung SET status='ABGESCHLOSSEN' WHERE id=?", SESSION);
        assertEquals(SITZUNG_ABGESCHLOSSEN, check(basis()).code());
        jdbc.update("UPDATE zaehlsitzung SET status='OFFEN' WHERE id=?", SESSION);
        jdbc.update("UPDATE zaehlblatt SET status='ABGESCHLOSSEN' WHERE id=?", SHEET);
        assertEquals(BLATT_ABGESCHLOSSEN, check(basis()).code());
    }

    @Test
    @Transactional
    void bothStaleStatusVersionsAreRejected() {
        jdbc.update("UPDATE zaehlsitzung SET status_version=2 WHERE id=?", SESSION);
        assertEquals(STATUSVERSION_VERALTET, check(basis()).code());
        jdbc.update("UPDATE zaehlsitzung SET status_version=1 WHERE id=?", SESSION);
        jdbc.update("UPDATE zaehlblatt SET status_version=2 WHERE id=?", SHEET);
        assertEquals(STATUSVERSION_VERALTET, check(basis()).code());
    }

    @Test
    void resultRevisionMustMatchAndAbsentResultIsZero() {
        assertEquals(ZULAESSIG, check(basis()).code());
        assertEquals(ERGEBNISREVISION_VERALTET, check(mit("basisRevision", 1L)).code());
        assertEquals(ERGEBNISREVISION_VERALTET, check(mit("zaehlpositionId", EXISTING)).code());
        var fields = fields();
        fields.put("zaehlpositionId", EXISTING);
        fields.put("basisRevision", 2L);
        assertEquals(ZULAESSIG, check(mapper.convertValue(fields, ErgebnisSpeichernInhalt.class)).code());
    }

    @Test
    void negativeQuantityAndMissingTargetsAreRejected() {
        assertEquals(UNGUELTIGE_ZAEHLMENGE, check(mit("zaehlmenge", -1)).code());
        for (String field : new String[] {"zaehlerlisteId", "zaehlsitzungId", "zaehlblattId", "zaehlpositionId"}) {
            assertEquals(ZIEL_NICHT_VORHANDEN, check(mit(field, UUID.randomUUID())).code());
        }
    }

    @Test
    @Transactional
    void staleJpaEntityCacheCannotHideChangedServerAssignment() {
        var cached = em.find(ZaehlerlisteEntity.class, LIST);
        assertEquals(1, cached.getZuweisungVersion());
        jdbc.update("UPDATE zaehlerliste SET zuweisung_version=2 WHERE id=?", LIST);
        assertEquals(1, cached.getZuweisungVersion());
        assertEquals(ZUWEISUNG_GEAENDERT, check(basis()).code());
    }

    @Test
    void transactionIsMandatoryAndReadOnlyCallerIsRejected() {
        assertThrows(IllegalTransactionStateException.class,
                () -> pruefer.pruefe(new GepruefterUrheber(USER), basis()));
        var readOnly = tx();
        readOnly.setReadOnly(true);
        assertThrows(IllegalStateException.class,
                () -> readOnly.execute(transaction -> pruefer.pruefe(new GepruefterUrheber(USER), basis())));
    }

    @Test
    void checkAndRegistrationShareTransactionWithoutCreatingAmountsOrPendingRows() {
        UUID requestId = UUID.randomUUID();
        tx().executeWithoutResult(transaction -> {
            assertTrue(pruefer.pruefe(new GepruefterUrheber(USER), basis()).zulaessig());
            assertEquals(RequestRegistrierungsstatus.NEU_REGISTRIERT,
                    registrierung.registriere(requestId, new GepruefterUrheber(USER), basis()).status());
            transaction.setRollbackOnly();
        });
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sync_request", Integer.class));
    }

    @Test
    void assignmentCannotChangeBeforeCheckingTransactionEnds() throws Exception {
        blocksMutation(basis(), "UPDATE zaehlerliste SET aktueller_zaehler_id='50000000-0000-4000-8000-000000000002', "
                + "zuweisung_version=2 WHERE id='" + LIST + "'");
        assertEquals(LISTE_NICHT_ZUGEWIESEN, check(basis()).code());
        assertEquals(ZUWEISUNG_GEAENDERT,
                tx().execute(transaction -> pruefer.pruefe(new GepruefterUrheber(OTHER_USER), basis())).code());
    }

    @Test
    void sessionCannotCloseBeforeCheckingTransactionEnds() throws Exception {
        blocksMutation(basis(), "UPDATE zaehlsitzung SET status='ABGESCHLOSSEN', status_version=2 WHERE id='" + SESSION + "'");
        assertEquals(SITZUNG_ABGESCHLOSSEN, check(basis()).code());
    }

    @Test
    void sheetCannotCloseBeforeCheckingTransactionEnds() throws Exception {
        blocksMutation(basis(), "UPDATE zaehlblatt SET status='ABGESCHLOSSEN', status_version=2 WHERE id='" + SHEET + "'");
        assertEquals(BLATT_ABGESCHLOSSEN, check(basis()).code());
    }

    @Test
    void existingRevisionCannotIncreaseBeforeCheckingTransactionEnds() throws Exception {
        var values = fields();
        values.put("zaehlpositionId", EXISTING);
        values.put("basisRevision", 2L);
        var request = mapper.convertValue(values, ErgebnisSpeichernInhalt.class);
        blocksMutation(request, "UPDATE zaehlergebnis SET revision=3 WHERE zaehlposition_id='" + EXISTING + "'");
        assertEquals(ERGEBNISREVISION_VERALTET, check(request).code());
    }

    @Test
    void firstResultInsertIsBlockedByPositionForeignKeyLock() throws Exception {
        blocksMutation(basis(), "INSERT INTO zaehlergebnis (id, zaehlposition_id, revision) VALUES "
                + "('97000000-0000-4000-8000-000000000002', '" + POSITION + "', 1)");
        assertEquals(ERGEBNISREVISION_VERALTET, check(basis()).code());
    }

    @Test
    void secondCheckingWriterSeesNewRevisionAfterFirstTransactionCommits() throws Exception {
        var values = fields();
        values.put("zaehlpositionId", EXISTING);
        values.put("basisRevision", 2L);
        var request = mapper.convertValue(values, ErgebnisSpeichernInhalt.class);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> tx().execute(transaction -> {
                assertTrue(pruefer.pruefe(new GepruefterUrheber(USER), request).zulaessig());
                locked.countDown();
                await(release);
                // Nur eine Revisionsaenderung im Test, keine Zaehlmengenveraenderung.
                jdbc.update("UPDATE zaehlergebnis SET revision=3 WHERE zaehlposition_id=?", EXISTING);
                return true;
            }));
            try {
                assertTrue(locked.await(10, TimeUnit.SECONDS));
                var second = executor.submit(() -> check(request));
                assertWaiting(second);
                release.countDown();
                assertTrue(first.get(10, TimeUnit.SECONDS));
                assertEquals(ERGEBNISREVISION_VERALTET, second.get(10, TimeUnit.SECONDS).code());
            } finally { release.countDown(); }
        }
    }

    private void blocksMutation(ErgebnisSpeichernInhalt request, String sql) throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> tx().execute(transaction -> {
                var result = pruefer.pruefe(new GepruefterUrheber(USER), request);
                assertEquals(ZULAESSIG, result.code());
                locked.countDown();
                await(release);
                return result;
            }));
            try {
                assertTrue(locked.await(10, TimeUnit.SECONDS));
                var writer = executor.submit(() -> tx().execute(transaction -> jdbc.update(sql)));
                assertWaiting(writer);
                release.countDown();
                assertEquals(ZULAESSIG, first.get(10, TimeUnit.SECONDS).code());
                assertEquals(1, writer.get(10, TimeUnit.SECONDS));
            } finally { release.countDown(); }
        }
    }

    private void assertWaiting(java.util.concurrent.Future<?> task) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean waiting = false;
        while (System.nanoTime() < deadline) {
            Integer waits = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'
                    """, Integer.class);
            if (waits != null && waits > 0) { waiting = true; break; }
            Thread.sleep(20);
        }
        assertTrue(waiting, "Konkurrierende PostgreSQL-Transaktion muss am Lock warten");
        assertFalse(task.isDone());
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test-Timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private ErgebnisPruefergebnis check(ErgebnisSpeichernInhalt input) {
        return tx().execute(transaction -> pruefer.pruefe(new GepruefterUrheber(USER), input));
    }

    private ErgebnisSpeichernInhalt basis() {
        return new ErgebnisSpeichernInhalt(1, SyncOperation.ERGEBNIS_SPEICHERN, LIST, SESSION, SHEET, POSITION,
                1L, 0L, 1L, 1L, 1L, 12, null);
    }

    private LinkedHashMap<String, Object> fields() {
        return mapper.convertValue(basis(), new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    private ErgebnisSpeichernInhalt mit(String field, Object value) {
        var fields = fields();
        fields.put(field, value);
        return mapper.convertValue(fields, ErgebnisSpeichernInhalt.class);
    }

    private TransactionTemplate tx() {
        var template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }
}
