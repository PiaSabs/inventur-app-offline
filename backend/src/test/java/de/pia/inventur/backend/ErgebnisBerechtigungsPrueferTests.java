package de.pia.inventur.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static de.pia.inventur.backend.service.sync.ErgebnisPruefcode.*;

import java.util.Optional;
import java.util.UUID;
import de.pia.inventur.backend.entity.*;
import de.pia.inventur.backend.repository.ErgebnisPruefRepository;
import de.pia.inventur.backend.repository.ErgebnisPruefRepository.*;
import de.pia.inventur.backend.service.sync.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ErgebnisBerechtigungsPrueferTests {

    private static final UUID USER = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID LIST = UUID.fromString("92000000-0000-4000-8000-000000000001");
    private static final UUID SESSION = UUID.fromString("93000000-0000-4000-8000-000000000001");
    private static final UUID SHEET = UUID.fromString("94000000-0000-4000-8000-000000000001");
    private static final UUID POSITION = UUID.fromString("96000000-0000-4000-8000-000000000001");
    private final ErgebnisPruefRepository repository = mock(ErgebnisPruefRepository.class);
    private final ErgebnisBerechtigungsPruefer pruefer = new ErgebnisBerechtigungsPruefer(repository, new ErgebnisRequestNormalisierer());

    @BeforeEach
    void setup() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        baseline();
    }

    @AfterEach
    void cleanup() { TransactionSynchronizationManager.clear(); }

    @Test
    void validRequestAndMissingResultAcceptNullZeroAndPositiveQuantity() {
        for (Integer quantity : new Integer[] {null, 0, 12}) {
            assertEquals(ZULAESSIG, pruefe(input(quantity, 0, 1, 1, 1)).code());
        }
    }

    @Test
    void nonexistentUserIsRejectedBeforeTargetLookup() {
        when(repository.sperreBenutzer(USER)).thenReturn(Optional.empty());
        assertEquals(BENUTZER_NICHT_VORHANDEN, pruefe(basis()).code());
        verify(repository, never()).sperreListe(any());
    }

    @Test
    void inactiveUserIsRejected() {
        when(repository.sperreBenutzer(USER)).thenReturn(Optional.of(new BenutzerStand(false, BenutzerRolle.ZAEHLER)));
        assertEquals(BENUTZER_INAKTIV, pruefe(basis()).code());
        verify(repository, never()).sperreListe(any());
    }

    @Test
    void administratorIsNotImplicitlyAuthorizedToCount() {
        when(repository.sperreBenutzer(USER)).thenReturn(Optional.of(new BenutzerStand(true, BenutzerRolle.ADMIN)));
        assertEquals(FALSCHE_ROLLE, pruefe(basis()).code());
    }

    @Test
    void foreignOrUnassignedListDoesNotRevealLaterState() {
        for (UUID owner : new UUID[] {null, UUID.randomUUID()}) {
            when(repository.sperreListe(LIST)).thenReturn(Optional.of(new ListenStand(owner, 99)));
            assertEquals(LISTE_NICHT_ZUGEWIESEN, pruefe(basis()).code());
        }
        verify(repository, never()).sperreSitzung(any());
        verify(repository, never()).sperreErgebnisrevision(any());
    }

    @Test
    void oldAssignmentVersionIsRejected() {
        when(repository.sperreListe(LIST)).thenReturn(Optional.of(new ListenStand(USER, 2)));
        assertEquals(ZUWEISUNG_GEAENDERT, pruefe(basis()).code());
    }

    @Test
    void everyWrongParentReferenceIsRejected() {
        when(repository.sperreSitzung(SESSION)).thenReturn(Optional.of(new SitzungsStand(UUID.randomUUID(), ZaehlsitzungStatus.OFFEN, 1)));
        assertEquals(FALSCHE_ZUORDNUNG, pruefe(basis()).code());
        baseline();
        when(repository.sperreBlatt(SHEET)).thenReturn(Optional.of(new BlattStand(UUID.randomUUID(), ZaehlblattStatus.OFFEN, 1)));
        assertEquals(FALSCHE_ZUORDNUNG, pruefe(basis()).code());
        baseline();
        when(repository.sperrePosition(POSITION)).thenReturn(Optional.of(new PositionsStand(UUID.randomUUID())));
        assertEquals(FALSCHE_ZUORDNUNG, pruefe(basis()).code());
    }

    @Test
    void closedSessionAndSheetAreRejected() {
        when(repository.sperreSitzung(SESSION)).thenReturn(Optional.of(new SitzungsStand(LIST, ZaehlsitzungStatus.ABGESCHLOSSEN, 1)));
        assertEquals(SITZUNG_ABGESCHLOSSEN, pruefe(basis()).code());
        baseline();
        when(repository.sperreBlatt(SHEET)).thenReturn(Optional.of(new BlattStand(SESSION, ZaehlblattStatus.ABGESCHLOSSEN, 1)));
        assertEquals(BLATT_ABGESCHLOSSEN, pruefe(basis()).code());
    }

    @Test
    void bothStatusVersionsMustMatchExactly() {
        assertEquals(STATUSVERSION_VERALTET, pruefe(input(12, 0, 1, 2, 1)).code());
        assertEquals(STATUSVERSION_VERALTET, pruefe(input(12, 0, 1, 1, 2)).code());
    }

    @Test
    void existingRevisionMustMatchAndMissingResultMeansZero() {
        when(repository.sperreErgebnisrevision(POSITION)).thenReturn(Optional.of(2L));
        assertEquals(ERGEBNISREVISION_VERALTET, pruefe(basis()).code());
        assertEquals(ZULAESSIG, pruefe(input(12, 2, 1, 1, 1)).code());
        when(repository.sperreErgebnisrevision(POSITION)).thenReturn(Optional.empty());
        assertEquals(ZULAESSIG, pruefe(basis()).code());
        assertEquals(ERGEBNISREVISION_VERALTET, pruefe(input(12, 1, 1, 1, 1)).code());
    }

    @Test
    void missingTargetResourcesAreRejected() {
        for (int target = 0; target < 4; target++) {
            baseline();
            switch (target) {
                case 0 -> when(repository.sperreListe(LIST)).thenReturn(Optional.empty());
                case 1 -> when(repository.sperreSitzung(SESSION)).thenReturn(Optional.empty());
                case 2 -> when(repository.sperreBlatt(SHEET)).thenReturn(Optional.empty());
                case 3 -> when(repository.sperrePosition(POSITION)).thenReturn(Optional.empty());
            }
            assertEquals(ZIEL_NICHT_VORHANDEN, pruefe(basis()).code());
        }
    }

    @Test
    void negativeQuantityAndInvalidRequestAreRejectedBeforeLookup() {
        assertEquals(UNGUELTIGE_ZAEHLMENGE, pruefe(input(-1, 0, 1, 1, 1)).code());
        assertEquals(UNGUELTIGER_REQUEST, pruefe(input(12, -1, 1, 1, 1)).code());
        verifyNoInteractions(repository);
    }

    @Test
    void callerTransactionAndTrustedIdentityAreRequired() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThrows(IllegalStateException.class, () -> pruefe(basis()));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThrows(IllegalStateException.class, () -> pruefe(basis()));
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        assertThrows(IllegalArgumentException.class, () -> pruefer.pruefe(null, basis()));
        assertThrows(IllegalArgumentException.class, () -> pruefe(null));
        verifyNoInteractions(repository);
    }

    private ErgebnisPruefergebnis pruefe(ErgebnisSpeichernInhalt input) {
        return pruefer.pruefe(new GepruefterUrheber(USER), input);
    }

    private ErgebnisSpeichernInhalt basis() { return input(12, 0, 1, 1, 1); }

    private ErgebnisSpeichernInhalt input(Integer quantity, long revision, long assignment, long sheetVersion, long sessionVersion) {
        return new ErgebnisSpeichernInhalt(1, SyncOperation.ERGEBNIS_SPEICHERN, LIST, SESSION, SHEET, POSITION,
                assignment, revision, 1L, sheetVersion, sessionVersion, quantity, null);
    }

    private void baseline() {
        when(repository.sperreBenutzer(USER)).thenReturn(Optional.of(new BenutzerStand(true, BenutzerRolle.ZAEHLER)));
        when(repository.sperreListe(LIST)).thenReturn(Optional.of(new ListenStand(USER, 1)));
        when(repository.sperreSitzung(SESSION)).thenReturn(Optional.of(new SitzungsStand(LIST, ZaehlsitzungStatus.OFFEN, 1)));
        when(repository.sperreBlatt(SHEET)).thenReturn(Optional.of(new BlattStand(SESSION, ZaehlblattStatus.OFFEN, 1)));
        when(repository.sperrePosition(POSITION)).thenReturn(Optional.of(new PositionsStand(SHEET)));
        when(repository.sperreErgebnisrevision(POSITION)).thenReturn(Optional.empty());
    }
}
