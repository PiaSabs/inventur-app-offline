package de.pia.inventur.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import de.pia.inventur.backend.entity.SyncOperation;
import de.pia.inventur.backend.entity.SyncRequestStatus;
import de.pia.inventur.backend.repository.SyncRequestsRepository;
import de.pia.inventur.backend.service.sync.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class RequestRegistrierungsServiceTests {

    private final SyncRequestsRepository repository = mock(SyncRequestsRepository.class);
    private final RequestWiederholungsService wiederholung = mock(RequestWiederholungsService.class);
    private final ErgebnisRequestNormalisierer normalisierer = new ErgebnisRequestNormalisierer();
    private final RequestRegistrierungsService service = new RequestRegistrierungsService(repository, normalisierer, wiederholung);
    private final UUID id = UUID.randomUUID();
    private final GepruefterUrheber urheber = new GepruefterUrheber(UUID.randomUUID());

    @BeforeEach
    void simulateTransactionLifecycleOnlyForUnitTests() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void clearSimulatedTransaction() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void newInsertReturnsProvisionalRegistrationAndSchedulesCommitGuard() {
        insertCount(1);
        var normalized = normalisierer.normalisiere(inhalt());
        var result = service.registriere(id, urheber, inhalt());
        assertEquals(RequestRegistrierungsstatus.NEU_REGISTRIERT, result.status());
        assertNull(result.entscheidung());
        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
        verify(repository).registriereFallsUnbekannt(id, urheber.benutzerId(), "ERGEBNIS_SPEICHERN",
                inhalt().zaehlerlisteId(), inhalt().zaehlsitzungId(), inhalt().zaehlblattId(), inhalt().zaehlpositionId(),
                1L, 0L, 1L, 1L, normalized.normalisierterInhalt(), normalized.inhaltFingerprint(), 1);
        verifyNoInteractions(wiederholung);
    }

    @Test
    void incompleteNewRegistrationCannotCommit() {
        insertCount(1);
        service.registriere(id, urheber, inhalt());
        when(repository.findeRegistrierungsstatus(id)).thenReturn(Optional.of("EINGEGANGEN"));
        assertThrows(IllegalStateException.class,
                () -> TransactionSynchronizationManager.getSynchronizations().getFirst().beforeCommit(false));
        verify(repository).flush();
    }

    @Test
    void eitherTerminalDecisionAllowsCommitGuardToComplete() {
        insertCount(1);
        service.registriere(id, urheber, inhalt());
        var guard = TransactionSynchronizationManager.getSynchronizations().getFirst();
        for (String state : new String[] {"AKZEPTIERT", "ABGELEHNT"}) {
            when(repository.findeRegistrierungsstatus(id)).thenReturn(Optional.of(state));
            assertDoesNotThrow(() -> guard.beforeCommit(false));
        }
    }

    @Test
    void missingNewRegistrationCannotCommit() {
        insertCount(1);
        service.registriere(id, urheber, inhalt());
        when(repository.findeRegistrierungsstatus(id)).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class,
                () -> TransactionSynchronizationManager.getSynchronizations().getFirst().beforeCommit(false));
    }

    @Test
    void existingRequestIsRecheckedAndAllOutcomesArePreserved() {
        insertCount(0);
        var decision = new GespeicherteRequestEntscheidung(SyncRequestStatus.ABGELEHNT,
                "DEMO_KONFLIKT", 409, "{}", Instant.parse("2026-10-14T08:00:00Z"));
        for (var state : RequestPruefstatus.values()) {
            if (state == RequestPruefstatus.NEUER_REQUEST) continue;
            var answer = state == RequestPruefstatus.BEKANNTE_ENTSCHEIDUNG ? decision : null;
            when(wiederholung.pruefe(id, urheber, inhalt())).thenReturn(new RequestPruefergebnis(state, answer));
            var result = service.registriere(id, urheber, inhalt());
            assertEquals(state.name(), result.status().name());
            assertEquals(answer, result.entscheidung());
            if (state == RequestPruefstatus.REQUEST_ID_KONFLIKT) assertEquals("REQUEST_ID_REUSED", result.fehlercode());
        }
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
    }

    @Test
    void vanishedConflictingRequestDoesNotBecomeFalseProcessingPermission() {
        insertCount(0);
        when(wiederholung.pruefe(id, urheber, inhalt())).thenReturn(new RequestPruefergebnis(RequestPruefstatus.NEUER_REQUEST, null));
        assertThrows(IllegalStateException.class, () -> service.registriere(id, urheber, inhalt()));
    }

    @Test
    void missingReadOnlyOrInvalidCallerIsRejectedBeforeInsert() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThrows(IllegalStateException.class, () -> service.registriere(id, urheber, inhalt()));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThrows(IllegalStateException.class, () -> service.registriere(id, urheber, inhalt()));
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        assertThrows(IllegalArgumentException.class, () -> service.registriere(null, urheber, inhalt()));
        assertThrows(IllegalArgumentException.class, () -> service.registriere(id, null, inhalt()));
        assertThrows(IllegalArgumentException.class, () -> service.registriere(id, urheber, null));
        verifyNoInteractions(repository);
    }

    @Test
    void provisionalRegistrationCannotCarryASuccessfulResponse() {
        var decision = new GespeicherteRequestEntscheidung(SyncRequestStatus.AKZEPTIERT, "DEMO", 200, "{}", Instant.now());
        assertThrows(IllegalArgumentException.class,
                () -> new RequestRegistrierungsergebnis(RequestRegistrierungsstatus.NEU_REGISTRIERT, decision));
    }

    private void insertCount(int count) {
        when(repository.registriereFallsUnbekannt(any(), any(), anyString(), any(), any(), any(), any(),
                anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyInt())).thenReturn(count);
    }

    private ErgebnisSpeichernInhalt inhalt() {
        return new ErgebnisSpeichernInhalt(1, SyncOperation.ERGEBNIS_SPEICHERN,
                UUID.fromString("10000000-0000-4000-8000-000000000001"),
                UUID.fromString("70000000-0000-4000-8000-000000000001"),
                UUID.fromString("82000000-0000-4000-8000-000000000001"),
                UUID.fromString("84000000-0000-4000-8000-000000000001"), 1L, 0L, 1L, 1L, 1L, 12, null);
    }
}
