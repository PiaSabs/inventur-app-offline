package de.pia.inventur.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import de.pia.inventur.backend.entity.*;
import de.pia.inventur.backend.repository.SyncRequestsRepository;
import de.pia.inventur.backend.service.sync.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Sql(scripts = {"/test-demo.sql", "/test-blatt-sitzungen.sql", "/test-positionen.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class RequestWiederholungIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("inventur_test").withUsername("inventur_test")
            .withPassword(UUID.randomUUID().toString());

    private static final UUID USER = UUID.fromString("50000000-0000-4000-8000-000000000002");
    private static final UUID LIST = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID SESSION = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final UUID SHEET = UUID.fromString("82000000-0000-4000-8000-000000000001");
    private static final UUID POSITION = UUID.fromString("84000000-0000-4000-8000-000000000001");
    private static final Instant TIME = Instant.parse("2026-10-14T08:00:00Z");

    @Autowired
    private RequestWiederholungsService service;
    @Autowired
    private ErgebnisRequestNormalisierer normalisierer;
    @Autowired
    private SyncRequestsRepository repository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @PersistenceContext
    private EntityManager em;

    private UUID acceptedId;

    @BeforeEach
    void prepareOwnFictitiousDecision() {
        assertEquals("inventur_test", jdbc.queryForObject("SELECT current_database()", String.class));
        acceptedId = seed(SyncRequestStatus.AKZEPTIERT);
    }

    @Test
    void newIdIsOnlyClassifiedAndNeverReservedOrProcessed() {
        UUID id = UUID.randomUUID();
        long before = repository.count();
        var result = pruefe(id, 12);
        assertEquals(RequestPruefstatus.NEUER_REQUEST, result.status());
        assertNull(result.entscheidung());
        assertFalse(repository.existsById(id));
        assertEquals(before, repository.count());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM zaehlergebnis", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ergebnis_historie", Integer.class));
    }

    @Test
    void repeatedAcceptedDecisionIsExactAndLeavesAllStoredFieldsUnchanged() {
        var before = jdbc.queryForMap("SELECT * FROM sync_request WHERE request_id=?", acceptedId);
        var first = pruefe(acceptedId, 12);
        var second = pruefe(acceptedId, 12);
        assertEquals(RequestPruefstatus.BEKANNTE_ENTSCHEIDUNG, first.status());
        assertEquals(first, second);
        assertEquals("{\"revision\":1}", first.entscheidung().antwortJson());
        assertEquals(TIME.plusSeconds(1), first.entscheidung().verarbeitetAm());
        assertEquals(before, jdbc.queryForMap("SELECT * FROM sync_request WHERE request_id=?", acceptedId));
    }

    @Test
    void repeatedRejectionIsReturnedAsStoredRejection() {
        var result = pruefe(seed(SyncRequestStatus.ABGELEHNT), 12);
        assertEquals(RequestPruefstatus.BEKANNTE_ENTSCHEIDUNG, result.status());
        assertEquals(SyncRequestStatus.ABGELEHNT, result.entscheidung().requestStatus());
        assertEquals(409, result.entscheidung().antwortHttpStatus());
        assertEquals("DEMO_KONFLIKT", result.entscheidung().entscheidungscode());
    }

    @Test
    void changedContentAndDifferentAuthorGetNoStoredResponse() {
        var conflict = pruefe(acceptedId, 13);
        assertEquals(RequestPruefstatus.REQUEST_ID_KONFLIKT, conflict.status());
        assertEquals("REQUEST_ID_REUSED", conflict.fehlercode());
        assertNull(conflict.entscheidung());
        var unauthorized = service.pruefe(acceptedId,
                new GepruefterUrheber(UUID.fromString("50000000-0000-4000-8000-000000000001")), inhalt(13));
        assertEquals(RequestPruefstatus.NICHT_BERECHTIGTER_ZUGRIFF, unauthorized.status());
        assertNull(unauthorized.entscheidung());
        assertNull(unauthorized.fehlercode());
    }

    @Test
    void enteredRequestHasNoSuccessfulDecision() {
        var result = pruefe(seed(SyncRequestStatus.EINGEGANGEN), 12);
        assertEquals(RequestPruefstatus.NOCH_IN_BEARBEITUNG, result.status());
        assertNull(result.entscheidung());
    }

    @Test
    void concurrentQueriesReplayOneStoredDecisionWithoutCreatingAnother() throws Exception {
        long before = repository.count();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            java.util.concurrent.Callable<RequestPruefergebnis> query = () -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return pruefe(acceptedId, 12);
            };
            var first = executor.submit(query);
            var second = executor.submit(query);
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
            var result = first.get(10, TimeUnit.SECONDS);
            assertEquals(RequestPruefstatus.BEKANNTE_ENTSCHEIDUNG, result.status());
            assertEquals(result, second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(before, repository.count());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ergebnis_historie", Integer.class));
    }

    @Test
    void concurrentUnknownQueriesDoNotClaimExclusiveReservation() throws Exception {
        UUID unknownId = UUID.randomUUID();
        long before = repository.count();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); return pruefe(unknownId, 12); });
            var second = executor.submit(() -> { start.await(); return pruefe(unknownId, 12); });
            start.countDown();
            assertEquals(RequestPruefstatus.NEUER_REQUEST, first.get(10, TimeUnit.SECONDS).status());
            assertEquals(RequestPruefstatus.NEUER_REQUEST, second.get(10, TimeUnit.SECONDS).status());
        }
        assertFalse(repository.existsById(unknownId));
        assertEquals(before, repository.count());
    }

    @Test
    void rollbackOfInsertedDecisionLeavesRequestUnknown() {
        UUID id = UUID.randomUUID();
        long before = repository.count();
        assertThrows(IllegalStateException.class, () -> tx().executeWithoutResult(transaction -> {
            repository.saveAndFlush(entity(id, SyncRequestStatus.AKZEPTIERT));
            // Intern in derselben Transaktion sichtbar, aber noch nicht extern bestaetigt.
            assertEquals(RequestPruefstatus.BEKANNTE_ENTSCHEIDUNG, pruefe(id, 12).status());
            throw new IllegalStateException("Demo-Rollback");
        }));
        assertEquals(RequestPruefstatus.NEUER_REQUEST, pruefe(id, 12).status());
        assertFalse(repository.existsById(id));
        assertEquals(before, repository.count());
    }

    @Test
    void databaseReadLockWaitsForWriterRollbackAndReturnsPendingState() throws Exception {
        waitsForWriter(false);
    }

    @Test
    void databaseReadLockWaitsForWriterCommitAndReturnsCommittedDecision() throws Exception {
        waitsForWriter(true);
    }

    private void waitsForWriter(boolean commit) throws Exception {
        UUID id = seed(SyncRequestStatus.EINGEGANGEN);
        var holdingLock = new CountDownLatch(1);
        var releaseWriter = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var writer = executor.submit(() -> {
                Runnable work = () -> tx().executeWithoutResult(transaction -> {
                    jdbc.update("""
                            UPDATE sync_request SET status='AKZEPTIERT', entscheidungscode='OK',
                                antwort_http_status=200, antwort_json='{"revision":1}', verarbeitet_am=?
                            WHERE request_id=?
                            """, java.time.OffsetDateTime.ofInstant(TIME.plusSeconds(1), java.time.ZoneOffset.UTC), id);
                    holdingLock.countDown();
                    try {
                        if (!releaseWriter.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Writer-Timeout");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    if (!commit) throw new IllegalStateException("Demo-Rollback");
                });
                if (commit) work.run();
                else assertThrows(IllegalStateException.class, work::run);
            });
            try {
                assertTrue(holdingLock.await(10, TimeUnit.SECONDS));
                var reader = executor.submit(() -> pruefe(id, 12));
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
                assertTrue(waiting, "PostgreSQL muss die Wiederholungsabfrage am Writer-Lock blockieren");
                assertFalse(reader.isDone());
                releaseWriter.countDown();
                var result = reader.get(10, TimeUnit.SECONDS);
                assertEquals(commit ? RequestPruefstatus.BEKANNTE_ENTSCHEIDUNG : RequestPruefstatus.NOCH_IN_BEARBEITUNG,
                        result.status());
                if (commit) assertEquals("{\"revision\":1}", result.entscheidung().antwortJson());
                else assertNull(result.entscheidung());
            } finally {
                releaseWriter.countDown();
            }
            writer.get(10, TimeUnit.SECONDS);
        }
    }

    private UUID seed(SyncRequestStatus status) {
        UUID id = UUID.randomUUID();
        tx().executeWithoutResult(transaction -> repository.saveAndFlush(entity(id, status)));
        return id;
    }

    private SyncRequestEntity entity(UUID id, SyncRequestStatus status) {
        var input = inhalt(12);
        var normalized = normalisierer.normalisiere(input);
        boolean pending = status == SyncRequestStatus.EINGEGANGEN;
        boolean accepted = status == SyncRequestStatus.AKZEPTIERT;
        return new SyncRequestEntity(id, em.getReference(BenutzerEntity.class, USER), input.operation(),
                em.getReference(ZaehlerlisteEntity.class, LIST), em.getReference(ZaehlsitzungEntity.class, SESSION),
                em.getReference(ZaehlblattEntity.class, SHEET), em.getReference(ZaehlpositionEntity.class, POSITION),
                1, 0L, 1L, 1L, normalized.normalisierterInhalt(), normalized.inhaltFingerprint(), normalized.inhaltFormatVersion(),
                status, pending ? null : accepted ? "OK" : "DEMO_KONFLIKT",
                pending ? null : accepted ? 200 : 409, pending ? null : "{\"revision\":1}",
                TIME, pending ? null : TIME.plusSeconds(1));
    }

    private ErgebnisSpeichernInhalt inhalt(int quantity) {
        return new ErgebnisSpeichernInhalt(1, SyncOperation.ERGEBNIS_SPEICHERN, LIST, SESSION, SHEET, POSITION,
                1L, 0L, 1L, 1L, 1L, quantity, null);
    }

    private RequestPruefergebnis pruefe(UUID id, int quantity) {
        return service.pruefe(id, new GepruefterUrheber(USER), inhalt(quantity));
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }
}
