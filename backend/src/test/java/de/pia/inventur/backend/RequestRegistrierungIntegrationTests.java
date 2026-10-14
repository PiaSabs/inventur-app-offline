package de.pia.inventur.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import de.pia.inventur.backend.entity.*;
import de.pia.inventur.backend.repository.SyncRequestsRepository;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Sql(scripts = {"/test-demo.sql", "/test-blatt-sitzungen.sql", "/test-positionen.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class RequestRegistrierungIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("inventur_test").withUsername("inventur_test")
            .withPassword(UUID.randomUUID().toString());

    private static final UUID USER = UUID.fromString("50000000-0000-4000-8000-000000000002");
    private static final UUID OTHER_USER = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final String RESPONSE = "{\"demo\":\"keine Mengenveraenderung\"}";

    @Autowired
    private RequestRegistrierungsService service;
    @Autowired
    private ErgebnisRequestNormalisierer normalisierer;
    @Autowired
    private SyncRequestsRepository repository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void callerTransactionIsMandatory() {
        assertThrows(IllegalTransactionStateException.class, () -> registriere(UUID.randomUUID(), USER, 12));
    }

    @Test
    void firstRegistrationStoresCompletePendingContentAndOnlyOneRow() {
        assertEquals("inventur_test", jdbc.queryForObject("SELECT current_database()", String.class));
        UUID id = UUID.randomUUID();
        tx().executeWithoutResult(transaction -> {
            var result = registriere(id, USER, 12);
            assertEquals(RequestRegistrierungsstatus.NEU_REGISTRIERT, result.status());
            assertNull(result.entscheidung());
            var row = repository.findById(id).orElseThrow();
            var normalized = normalisierer.normalisiere(inhalt(12));
            assertEquals(SyncRequestStatus.EINGEGANGEN, row.getStatus());
            assertEquals(USER, row.getUrheber().getId());
            assertEquals(normalized.normalisierterInhalt(), row.getNormalisierterInhalt());
            assertEquals(normalized.inhaltFingerprint(), row.getInhaltFingerprint());
            assertEquals(1, row.getInhaltFormatVersion());
            assertNotNull(row.getEingegangenAm());
            assertNull(row.getAntwortJson());
            assertNull(row.getVerarbeitetAm());
            assertEquals(1, count(id));
            decideOnlyForTest(id, false);
        });
        assertEquals(1, count(id));
        assertNoQuantityChanges();
    }

    @Test
    void duplicateWithinSameTransactionIsPendingNotSecondProcessingPermission() {
        UUID id = UUID.randomUUID();
        tx().executeWithoutResult(transaction -> {
            assertEquals(RequestRegistrierungsstatus.NEU_REGISTRIERT, registriere(id, USER, 12).status());
            var repeated = registriere(id, USER, 12);
            assertEquals(RequestRegistrierungsstatus.NOCH_IN_BEARBEITUNG, repeated.status());
            assertNull(repeated.entscheidung());
            assertEquals(1, count(id));
            decideOnlyForTest(id, false);
        });
    }

    @Test
    void decidedRequestIsReplayedWithoutChangingAnyStoredField() {
        UUID id = seedDecision(false);
        var before = jdbc.queryForMap("SELECT * FROM sync_request WHERE request_id=?", id);
        var result = tx().execute(transaction -> registriere(id, USER, 12));
        assertNotNull(result);
        assertEquals(RequestRegistrierungsstatus.BEKANNTE_ENTSCHEIDUNG, result.status());
        assertEquals(RESPONSE, result.entscheidung().antwortJson());
        assertEquals(before, jdbc.queryForMap("SELECT * FROM sync_request WHERE request_id=?", id));
        assertEquals(1, count(id));
        assertNoQuantityChanges();
    }

    @Test
    void rejectedDecisionIsReplayedAsOriginalRejection() {
        UUID id = seedDecision(true);
        var result = tx().execute(transaction -> registriere(id, USER, 12));
        assertNotNull(result);
        assertEquals(RequestRegistrierungsstatus.BEKANNTE_ENTSCHEIDUNG, result.status());
        assertEquals(SyncRequestStatus.ABGELEHNT, result.entscheidung().requestStatus());
        assertEquals(409, result.entscheidung().antwortHttpStatus());
    }

    @Test
    void changedContentAndDifferentAuthorCannotAccessStoredAnswer() {
        UUID id = seedDecision(false);
        var changed = tx().execute(transaction -> registriere(id, USER, 13));
        assertNotNull(changed);
        assertEquals(RequestRegistrierungsstatus.REQUEST_ID_KONFLIKT, changed.status());
        assertEquals("REQUEST_ID_REUSED", changed.fehlercode());
        assertNull(changed.entscheidung());
        var unauthorized = tx().execute(transaction -> registriere(id, OTHER_USER, 12));
        assertNotNull(unauthorized);
        assertEquals(RequestRegistrierungsstatus.NICHT_BERECHTIGTER_ZUGRIFF, unauthorized.status());
        assertNull(unauthorized.entscheidung());
    }

    @Test
    void explicitRollbackRemovesRegistrationAndAllowsFullRetry() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> tx().executeWithoutResult(transaction -> {
            assertEquals(RequestRegistrierungsstatus.NEU_REGISTRIERT, registriere(id, USER, 12).status());
            throw new IllegalStateException("Demo-Rollback");
        }));
        assertEquals(0, count(id));
        tx().executeWithoutResult(transaction -> {
            assertEquals(RequestRegistrierungsstatus.NEU_REGISTRIERT, registriere(id, USER, 12).status());
            decideOnlyForTest(id, false);
        });
        assertEquals(1, count(id));
    }

    @Test
    void unfinishedRegistrationCannotLeaveAnOrphanAfterCommitAttempt() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> tx().executeWithoutResult(transaction -> registriere(id, USER, 12)));
        assertEquals(0, count(id));
        assertNoQuantityChanges();
    }

    @Test
    void concurrentIdenticalRegistrationsCreateOneRowAndOneProcessingOwner() throws Exception {
        concurrent(12, USER, false, RequestRegistrierungsstatus.BEKANNTE_ENTSCHEIDUNG);
    }

    @Test
    void concurrentDifferentContentGetsConflictAfterWinnerCommits() throws Exception {
        concurrent(13, USER, false, RequestRegistrierungsstatus.REQUEST_ID_KONFLIKT);
    }

    @Test
    void concurrentDifferentAuthorGetsNoOriginalAnswer() throws Exception {
        concurrent(12, OTHER_USER, false, RequestRegistrierungsstatus.NICHT_BERECHTIGTER_ZUGRIFF);
    }

    @Test
    void waitingContenderTakesOverOnlyAfterFirstTransactionRollsBack() throws Exception {
        concurrent(12, USER, true, RequestRegistrierungsstatus.NEU_REGISTRIERT);
    }

    private void concurrent(int otherQuantity, UUID otherUser, boolean rollbackFirst,
            RequestRegistrierungsstatus expected) throws Exception {
        UUID id = UUID.randomUUID();
        var inserted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> tx().execute(transaction -> {
                var result = registriere(id, USER, 12);
                assertEquals(RequestRegistrierungsstatus.NEU_REGISTRIERT, result.status());
                inserted.countDown();
                await(release);
                if (rollbackFirst) throw new IllegalStateException("Demo-Rollback");
                decideOnlyForTest(id, false);
                return result;
            }));
            try {
                assertTrue(inserted.await(10, TimeUnit.SECONDS));
                var second = executor.submit(() -> tx().execute(transaction -> {
                    var result = registriere(id, otherUser, otherQuantity);
                    if (result.status() == RequestRegistrierungsstatus.NEU_REGISTRIERT) decideOnlyForTest(id, false);
                    return result;
                }));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    Integer waits = jdbc.queryForObject("""
                            SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()
                            AND wait_event_type='Lock' AND query LIKE '%sync_request%'
                            """, Integer.class);
                    if (waits != null && waits > 0) { waiting = true; break; }
                    Thread.sleep(20);
                }
                assertTrue(waiting, "Zweiter INSERT muss an der PostgreSQL-Eindeutigkeitspruefung warten");
                assertFalse(second.isDone());
                release.countDown();
                if (rollbackFirst) {
                    var error = assertThrows(ExecutionException.class, () -> first.get(10, TimeUnit.SECONDS));
                    assertInstanceOf(IllegalStateException.class, error.getCause());
                } else {
                    assertEquals(RequestRegistrierungsstatus.NEU_REGISTRIERT, first.get(10, TimeUnit.SECONDS).status());
                }
                var result = second.get(10, TimeUnit.SECONDS);
                assertEquals(expected, result.status());
                if (expected == RequestRegistrierungsstatus.BEKANNTE_ENTSCHEIDUNG) assertEquals(RESPONSE, result.entscheidung().antwortJson());
                else assertNull(result.entscheidung());
            } finally {
                release.countDown();
            }
        }
        assertEquals(1, count(id));
        assertEquals(normalisierer.normalisiere(inhalt(12)).inhaltFingerprint(),
                jdbc.queryForObject("SELECT inhalt_fingerprint FROM sync_request WHERE request_id=?", String.class, id));
        assertNoQuantityChanges();
    }

    // Nur Testvorbereitung: simuliert eine fertige Entscheidung ohne Mengen-/Historienaenderung.
    // Der Produktionsservice implementiert diesen Schritt absichtlich noch nicht.
    private void decideOnlyForTest(UUID id, boolean rejected) {
        jdbc.update("""
                UPDATE sync_request SET status=?, entscheidungscode=?, antwort_http_status=?,
                    antwort_json=?, verarbeitet_am=clock_timestamp() WHERE request_id=?
                """, rejected ? "ABGELEHNT" : "AKZEPTIERT", rejected ? "DEMO_ABGELEHNT" : "DEMO_OK",
                rejected ? 409 : 200, RESPONSE, id);
    }

    private UUID seedDecision(boolean rejected) {
        UUID id = UUID.randomUUID();
        tx().executeWithoutResult(transaction -> {
            registriere(id, USER, 12);
            decideOnlyForTest(id, rejected);
        });
        return id;
    }

    private RequestRegistrierungsergebnis registriere(UUID id, UUID user, int quantity) {
        return service.registriere(id, new GepruefterUrheber(user), inhalt(quantity));
    }

    private ErgebnisSpeichernInhalt inhalt(int quantity) {
        return new ErgebnisSpeichernInhalt(1, SyncOperation.ERGEBNIS_SPEICHERN,
                UUID.fromString("10000000-0000-4000-8000-000000000001"),
                UUID.fromString("70000000-0000-4000-8000-000000000001"),
                UUID.fromString("82000000-0000-4000-8000-000000000001"),
                UUID.fromString("84000000-0000-4000-8000-000000000001"), 1L, 0L, 1L, 1L, 1L, quantity, null);
    }

    private int count(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM sync_request WHERE request_id=?", Integer.class, id);
    }

    private void assertNoQuantityChanges() {
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM zaehlergebnis", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ergebnis_historie", Integer.class));
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test-Timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private TransactionTemplate tx() {
        var template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }
}
