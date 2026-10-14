package de.pia.inventur.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import de.pia.inventur.backend.entity.*;
import de.pia.inventur.backend.repository.SyncRequestsRepository;
import de.pia.inventur.backend.service.sync.*;
import org.junit.jupiter.api.Test;

class RequestWiederholungsServiceTests {

    private static final UUID ID = UUID.fromString("88000000-0000-4000-8000-000000000001");
    private static final UUID USER = UUID.fromString("50000000-0000-4000-8000-000000000002");
    private final SyncRequestsRepository repository = mock(SyncRequestsRepository.class);
    private final ErgebnisRequestNormalisierer normalisierer = new ErgebnisRequestNormalisierer();
    private final RequestWiederholungsService service = new RequestWiederholungsService(repository, normalisierer);

    @Test
    void unknownRequestIsNewWithoutResponseOrWrite() {
        when(repository.findeZurWiederholungspruefung(ID)).thenReturn(Optional.empty());
        var result = pruefe(inhalt(12));
        assertEquals(RequestPruefstatus.NEUER_REQUEST, result.status());
        assertNull(result.entscheidung());
        verify(repository).findeZurWiederholungspruefung(ID);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void identicalAcceptedRequestReturnsExactStoredDecision() {
        bekannt(SyncRequestStatus.AKZEPTIERT);
        var result = pruefe(inhalt(12));
        assertEquals(RequestPruefstatus.BEKANNTE_ENTSCHEIDUNG, result.status());
        assertEquals(SyncRequestStatus.AKZEPTIERT, result.entscheidung().requestStatus());
        assertEquals(200, result.entscheidung().antwortHttpStatus());
        assertEquals("{\"revision\":1}", result.entscheidung().antwortJson());
        assertEquals("OK", result.entscheidung().entscheidungscode());
        assertEquals(Instant.parse("2026-10-14T08:00:01Z"), result.entscheidung().verarbeitetAm());
    }

    @Test
    void identicalRejectedRequestReturnsOriginalRejection() {
        bekannt(SyncRequestStatus.ABGELEHNT);
        var result = pruefe(inhalt(12));
        assertEquals(RequestPruefstatus.BEKANNTE_ENTSCHEIDUNG, result.status());
        assertEquals(SyncRequestStatus.ABGELEHNT, result.entscheidung().requestStatus());
        assertEquals(409, result.entscheidung().antwortHttpStatus());
        assertEquals("DEMO_KONFLIKT", result.entscheidung().entscheidungscode());
    }

    @Test
    void changedContentReturnsRequestIdReusedWithoutStoredAnswer() {
        bekannt(SyncRequestStatus.AKZEPTIERT);
        var result = pruefe(inhalt(13));
        assertEquals(RequestPruefstatus.REQUEST_ID_KONFLIKT, result.status());
        assertEquals("REQUEST_ID_REUSED", result.fehlercode());
        assertNull(result.entscheidung());
    }

    @Test
    void differentAuthorIsCheckedBeforeStoredContentAndAnswer() {
        var normalized = normalisierer.normalisiere(inhalt(12));
        var row = spy(gespeichert(SyncRequestStatus.AKZEPTIERT, normalized.normalisierterInhalt(),
                normalized.inhaltFingerprint(), 1, 1));
        when(repository.findeZurWiederholungspruefung(ID)).thenReturn(Optional.of(row));
        var result = service.pruefe(ID, new GepruefterUrheber(UUID.randomUUID()), inhalt(13));
        assertEquals(RequestPruefstatus.NICHT_BERECHTIGTER_ZUGRIFF, result.status());
        assertNull(result.entscheidung());
        assertNull(result.fehlercode());
        verify(row, never()).getNormalisierterInhalt();
        verify(row, never()).getAntwortJson();
        verify(row, never()).getStatus();
    }

    @Test
    void pendingRequestIsNotReportedAsSuccessful() {
        bekannt(SyncRequestStatus.EINGEGANGEN);
        var result = pruefe(inhalt(12));
        assertEquals(RequestPruefstatus.NOCH_IN_BEARBEITUNG, result.status());
        assertNull(result.entscheidung());
    }

    @Test
    void storedFormatCanonicalContentAndMetadataMustAlsoMatch() {
        var normalized = normalisierer.normalisiere(inhalt(12));
        for (var row : new SyncRequestEntity[] {
                gespeichert(SyncRequestStatus.AKZEPTIERT, normalized.normalisierterInhalt(), normalized.inhaltFingerprint(), 2, 1),
                gespeichert(SyncRequestStatus.AKZEPTIERT, "{}", normalized.inhaltFingerprint(), 1, 1),
                gespeichert(SyncRequestStatus.AKZEPTIERT, normalized.normalisierterInhalt(), "a".repeat(64), 1, 1),
                gespeichert(SyncRequestStatus.AKZEPTIERT, normalized.normalisierterInhalt(), normalized.inhaltFingerprint(), 1, 2)}) {
            when(repository.findeZurWiederholungspruefung(ID)).thenReturn(Optional.of(row));
            var result = pruefe(inhalt(12));
            assertEquals(RequestPruefstatus.REQUEST_ID_KONFLIKT, result.status());
            assertNull(result.entscheidung());
        }
    }

    @Test
    void invalidInputIsRejectedBeforeDatabaseAccess() {
        assertThrows(IllegalArgumentException.class, () -> service.pruefe(null, new GepruefterUrheber(USER), inhalt(12)));
        assertThrows(IllegalArgumentException.class, () -> service.pruefe(ID, null, inhalt(12)));
        assertThrows(IllegalArgumentException.class, () -> service.pruefe(ID, new GepruefterUrheber(USER), null));
        assertThrows(IllegalArgumentException.class, () -> new GepruefterUrheber(null));
        verifyNoInteractions(repository);
    }

    @Test
    void nonDecisionStatusesCannotCarryForeignAnswerData() {
        var decision = new GespeicherteRequestEntscheidung(SyncRequestStatus.AKZEPTIERT,
                "OK", 200, "{}", Instant.parse("2026-10-14T08:00:01Z"));
        for (var status : RequestPruefstatus.values()) {
            if (status != RequestPruefstatus.BEKANNTE_ENTSCHEIDUNG) {
                assertThrows(IllegalArgumentException.class, () -> new RequestPruefergebnis(status, decision));
            }
        }
        assertThrows(IllegalArgumentException.class,
                () -> new RequestPruefergebnis(RequestPruefstatus.BEKANNTE_ENTSCHEIDUNG, null));
        assertThrows(IllegalArgumentException.class,
                () -> new GespeicherteRequestEntscheidung(SyncRequestStatus.EINGEGANGEN, "OK", 200, "{}", Instant.now()));
    }

    private RequestPruefergebnis pruefe(ErgebnisSpeichernInhalt input) {
        return service.pruefe(ID, new GepruefterUrheber(USER), input);
    }

    private void bekannt(SyncRequestStatus status) {
        var normalized = normalisierer.normalisiere(inhalt(12));
        when(repository.findeZurWiederholungspruefung(ID)).thenReturn(Optional.of(
                gespeichert(status, normalized.normalisierterInhalt(), normalized.inhaltFingerprint(), 1, 1)));
    }

    private ErgebnisSpeichernInhalt inhalt(int quantity) {
        return new ErgebnisSpeichernInhalt(1, SyncOperation.ERGEBNIS_SPEICHERN,
                UUID.fromString("10000000-0000-4000-8000-000000000001"),
                UUID.fromString("70000000-0000-4000-8000-000000000001"),
                UUID.fromString("82000000-0000-4000-8000-000000000001"),
                UUID.fromString("84000000-0000-4000-8000-000000000001"), 1L, 0L, 1L, 1L, 1L, quantity, null);
    }

    private SyncRequestEntity gespeichert(SyncRequestStatus status, String body, String hash, int format, long assignment) {
        var input = inhalt(12);
        var list = new ZaehlerlisteEntity(input.zaehlerlisteId(), "DEMO", "Demo-Liste");
        var session = new ZaehlsitzungEntity(input.zaehlsitzungId(), list, java.time.LocalDate.of(2026, 10, 14),
                1, ZaehlsitzungStatus.OFFEN, 1, Instant.parse("2026-10-14T08:00:00Z"), null);
        var sheet = new ZaehlblattEntity(input.zaehlblattId(), session, "1", ZaehlblattStatus.OFFEN, 1, null, null, null);
        var position = new ZaehlpositionEntity(input.zaehlpositionId(), sheet,
                new ArtikelEntity(UUID.randomUUID(), "DEMO", "Demo-Artikel"), 1, null, null, null);
        boolean pending = status == SyncRequestStatus.EINGEGANGEN;
        boolean accepted = status == SyncRequestStatus.AKZEPTIERT;
        return new SyncRequestEntity(ID, new BenutzerEntity(USER, "Demo", BenutzerRolle.ZAEHLER, true),
                input.operation(), list, session, sheet, position, assignment, 0L, 1L, 1L,
                body, hash, format, status, pending ? null : accepted ? "OK" : "DEMO_KONFLIKT",
                pending ? null : accepted ? 200 : 409, pending ? null : "{\"revision\":1}",
                Instant.parse("2026-10-14T08:00:00Z"), pending ? null : Instant.parse("2026-10-14T08:00:01Z"));
    }
}
