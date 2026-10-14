package de.pia.inventur.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import de.pia.inventur.backend.repository.ErgebnisSpeicherRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.util.AopTestUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ErgebnisHttpIntegrationTests.TestDecoder.class)
@Sql(scripts = "/test-demo.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Sql(scripts = "/test-berechtigungspruefung.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(scripts = "/cleanup-ergebnis-speichern.sql", executionPhase = Sql.ExecutionPhase.AFTER_TEST_METHOD)
class ErgebnisHttpIntegrationTests {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("inventur_test").withUsername("inventur_test").withPassword(UUID.randomUUID().toString());
    private static final JwtTestSchluessel keys = new JwtTestSchluessel();
    private static final String LIST = "92000000-0000-4000-8000-000000000001";
    private static final String SESSION = "93000000-0000-4000-8000-000000000001";
    private static final String SHEET = "94000000-0000-4000-8000-000000000001";
    private static final String POSITION = "96000000-0000-4000-8000-000000000001";
    private static final String PATH = path(SESSION, POSITION);
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private ErgebnisSpeicherRepository daten;
    private ErgebnisSpeicherRepository schreibzugriff;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @TestConfiguration(proxyBeanMethods = false)
    static class TestDecoder { @Bean @Primary JwtDecoder isolierterTestDecoder() { return keys.decoder(); } }

    @BeforeEach
    void gepruefteTestidentitaeten() {
        schreibzugriff = AopTestUtils.getUltimateTargetObject(daten);
        jdbc.update("UPDATE benutzer SET aktiv=true, rolle='ZAEHLER' WHERE id IN ('50000000-0000-4000-8000-000000000001','50000000-0000-4000-8000-000000000002')");
        jdbc.update("UPDATE benutzer SET oidc_issuer=?, oidc_subject=? WHERE id='50000000-0000-4000-8000-000000000001'", JwtTestSchluessel.ISSUER, JwtTestSchluessel.SUBJECT);
        jdbc.update("UPDATE benutzer SET oidc_issuer=?, oidc_subject='demo-counter-b' WHERE id='50000000-0000-4000-8000-000000000002'", JwtTestSchluessel.ISSUER);
        jdbc.update("UPDATE benutzer SET oidc_issuer=?, oidc_subject='demo-admin' WHERE id='50000000-0000-4000-8000-000000000003'", JwtTestSchluessel.ISSUER);
    }

    @Test
    void firstAndSecondWriteCommitExpectedRevisionsAndGetReflectsUnchangedContract() throws Exception {
        assertEquals("inventur_test", jdbc.queryForObject("SELECT current_database()", String.class));
        var request = request(); var first = put(request); assertEquals(200, first.statusCode());
        var json = mapper.readTree(first.body()); assertEquals(8, json.size());
        assertEquals(request.get("requestId").toString(), json.get("requestId").asString());
        assertEquals("AKZEPTIERT", json.get("entscheidung").asString());
        assertEquals(POSITION, json.get("zaehlpositionId").asString()); assertEquals(7, json.get("bestaetigteLokaleRevision").asLong());
        assertEquals(1, json.get("ergebnisRevision").asLong()); assertCounts(1, 1, 1);
        var secondRequest = request(); secondRequest.put("basisRevision", 1L); secondRequest.put("lokaleRevision", 8L);
        var second = put(secondRequest); assertEquals(200, second.statusCode());
        assertEquals(2, mapper.readTree(second.body()).get("ergebnisRevision").asLong()); assertCounts(1, 2, 2);
        var detail = get("/api/v1/sitzungen/" + SESSION + "/blaetter/" + SHEET, keys.token()); assertEquals(200, detail.statusCode());
        var result = mapper.readTree(detail.body()).get("positionen").get(0).get("ergebnis");
        assertEquals(12, result.get("zaehlmenge").asInt()); assertEquals(2, result.get("revision").asLong());
        assertEquals(200, get("/api/v1/health", null).statusCode());
    }

    static Stream<Arguments> quantities() {
        return Stream.of(Arguments.of((Integer)null, "Demo-Notiz ohne Menge"), Arguments.of(0, null),
                Arguments.of(12, "Demo: Stück 🟩\r\n"), Arguments.of(Integer.MAX_VALUE, "Demo-Grenzwert"));
    }
    @ParameterizedTest @MethodSource("quantities")
    void quantitiesAndNotesArePreservedWithoutCoercion(Integer quantity, String note) throws Exception {
        var request = request(); request.put("zaehlmenge", quantity); request.put("anmerkung", note);
        var result = put(request); assertEquals(200, result.statusCode());
        assertEquals(quantity, jdbc.queryForObject("SELECT zaehlmenge FROM zaehlergebnis WHERE zaehlposition_id=?::uuid", Integer.class, POSITION));
        assertEquals(note, jdbc.queryForObject("SELECT anmerkung FROM ergebnis_historie", String.class));
        assertEquals(result.body(), jdbc.queryForObject("SELECT antwort_json FROM sync_request", String.class));
        assertEquals(result.body(), put(request).body()); assertCounts(1, 1, 1);
    }

    static Stream<Arguments> invalidFields() {
        return Stream.of(Arguments.of("requestId", null), Arguments.of("requestId", "kein-uuid"),
                Arguments.of("zaehlerlisteId", 42), Arguments.of("zaehlblattId", "1-1-1-1-1"),
                Arguments.of("zaehlmenge", -1), Arguments.of("zaehlmenge", "12"), Arguments.of("zaehlmenge", 1.5),
                Arguments.of("zaehlmenge", 2147483648L), Arguments.of("zaehlmenge", true),
                Arguments.of("anmerkung", 7), Arguments.of("anmerkung", "Demo\u0000ungueltig"),
                Arguments.of("basisRevision", "0"), Arguments.of("basisRevision", null), Arguments.of("basisRevision", 1.0),
                Arguments.of("lokaleRevision", new java.math.BigInteger("9223372036854775808")),
                Arguments.of("erwarteteBlattStatusVersion", 0), Arguments.of("zuweisungVersion", -1),
                Arguments.of("benutzerId", "50000000-0000-4000-8000-000000000002"),
                Arguments.of("operation", "BLATT_ABSCHLIESSEN"), Arguments.of("sitzungId", SESSION));
    }
    @ParameterizedTest @MethodSource("invalidFields")
    void invalidTypesUnknownFieldsAndOverflowReturnGeneric400WithoutWrites(String field, Object value) throws Exception {
        var request = request(); request.put(field, value);
        var result = put(request); assertEquals(400, result.statusCode()); assertError(result, "UNGUELTIGER_REQUEST"); assertCounts(0, 0, 0);
    }

    @Test
    void everyFieldIsRequiredAndDuplicateOrTrailingJsonIsRejected() throws Exception {
        for (String field : request().keySet()) {
            var incomplete = request(); incomplete.remove(field);
            assertEquals(400, put(incomplete).statusCode());
        }
        String valid = mapper.writeValueAsString(request());
        for (String invalid : new String[] {"", "null", "[]", "{", valid + " {}", valid.substring(0, valid.length()-1) + ",\"basisRevision\":0}"}) {
            assertEquals(400, send(PATH, invalid, keys.token()).statusCode());
        }
        for (String invalidPath : new String[] {path("keine-uuid", POSITION), path(SESSION, "keine-uuid"), path("1-1-1-1-1", POSITION)}) {
            assertEquals(400, send(invalidPath, valid, keys.token()).statusCode());
        }
        assertCounts(0, 0, 0);
    }

    @Test
    void authenticationAndAdminCannotBeBypassed() throws Exception {
        String body = mapper.writeValueAsString(request());
        assertEquals(401, send(PATH, body, null).statusCode());
        assertEquals(401, send(PATH, body, new JwtTestSchluessel().token()).statusCode());
        assertEquals(403, send(PATH, body, token("demo-admin")).statusCode());
        jdbc.update("UPDATE benutzer SET aktiv=false WHERE id='50000000-0000-4000-8000-000000000001'");
        assertEquals(401, send(PATH, body, keys.token()).statusCode()); assertCounts(0, 0, 0);
    }

    static Stream<Arguments> deniedChanges() {
        return Stream.of(Arguments.of("zuweisungVersion", 2L, 409, "ZUWEISUNG_GEAENDERT"),
                Arguments.of("basisRevision", 1L, 409, "ERGEBNISREVISION_VERALTET"),
                Arguments.of("erwarteteBlattStatusVersion", 2L, 409, "STATUSVERSION_VERALTET"),
                Arguments.of("erwarteteSitzungStatusVersion", 2L, 409, "STATUSVERSION_VERALTET"),
                Arguments.of("zaehlerlisteId", "92000000-0000-4000-8000-000000000002", 404, "RESSOURCE_NICHT_GEFUNDEN"),
                Arguments.of("zaehlerlisteId", UUID.randomUUID(), 404, "RESSOURCE_NICHT_GEFUNDEN"),
                Arguments.of("zaehlblattId", "94000000-0000-4000-8000-000000000003", 404, "RESSOURCE_NICHT_GEFUNDEN"));
    }
    @ParameterizedTest @MethodSource("deniedChanges")
    void newChangesRespectAuthorizationAndAllVersions(String field, Object value, int status, String code) throws Exception {
        var request = request(); request.put(field, value);
        var result = put(request); assertEquals(status, result.statusCode());
        assertError(result, status == 409 ? "KONFLIKT_ARCHIVIERT_" + code : code);
        assertCounts(0, 0, status == 409 ? 1 : 0);
    }

    @Test
    void pathIdsAreBoundToCanonicalContentAndWrongHierarchyCannotWrite() throws Exception {
        String body = mapper.writeValueAsString(request());
        assertEquals(404, send(path("93000000-0000-4000-8000-000000000002", POSITION), body, keys.token()).statusCode());
        assertEquals(404, send(path(SESSION, "96000000-0000-4000-8000-000000000003"), body, keys.token()).statusCode());
        assertEquals(404, send(path(SESSION, UUID.randomUUID().toString()), body, keys.token()).statusCode());
        var original = request(); var accepted = put(original); assertEquals(200, accepted.statusCode());
        assertEquals(409, send(path(SESSION, "96000000-0000-4000-8000-000000000002"), mapper.writeValueAsString(original), keys.token()).statusCode());
        assertCounts(1, 1, 1);
    }

    @Test
    void replayAfterReassignmentClosureAndLaterRevisionReturnsOriginalResponseExactly() throws Exception {
        var original = request(); var accepted = put(original); assertEquals(200, accepted.statusCode());
        var later = request(); later.put("basisRevision", 1L); assertEquals(200, put(later).statusCode());
        assertEquals(accepted.body(), put(original).body());
        jdbc.update("UPDATE zaehlerliste SET aktueller_zaehler_id='50000000-0000-4000-8000-000000000002', zuweisung_version=2 WHERE id=?::uuid", LIST);
        var repeated = put(original); assertEquals(200, repeated.statusCode()); assertEquals(accepted.body(), repeated.body());
        jdbc.update("UPDATE zaehlblatt SET status='ABGESCHLOSSEN', status_version=2 WHERE id=?::uuid", SHEET);
        assertEquals(accepted.body(), put(original).body());
        jdbc.update("UPDATE zaehlsitzung SET status='ABGESCHLOSSEN', status_version=2 WHERE id=?::uuid", SESSION);
        assertEquals(accepted.body(), put(original).body()); assertCounts(1, 2, 2);
        var newChange = request(); newChange.put("basisRevision", 2L);
        assertEquals(404, put(newChange).statusCode()); assertCounts(1, 2, 2);
    }

    @Test
    void changedContentAndForeignRequestIdUseNeverRevealOrOverwriteDecision() throws Exception {
        var original = request(); var accepted = put(original); assertEquals(200, accepted.statusCode());
        var changed = new LinkedHashMap<>(original); changed.put("zaehlmenge", 13);
        var conflict = put(changed); assertEquals(409, conflict.statusCode()); assertError(conflict, "REQUEST_ID_REUSED");
        var foreign = send(PATH, mapper.writeValueAsString(original), token("demo-counter-b"));
        assertEquals(404, foreign.statusCode()); assertError(foreign, "RESSOURCE_NICHT_GEFUNDEN");
        assertFalse(foreign.body().contains(original.get("requestId").toString()));
        assertEquals(accepted.body(), put(original).body()); assertCounts(1, 1, 1);
    }

    @Test
    void storedAcknowledgmentReplayDoesNotGrantAdminNewWriteRights() throws Exception {
        var original = request(); var accepted = put(original); assertEquals(200, accepted.statusCode());
        jdbc.update("UPDATE benutzer SET rolle='ADMIN' WHERE id='50000000-0000-4000-8000-000000000001'");
        assertEquals(accepted.body(), put(original).body());
        var next = request(); next.put("basisRevision", 1L); assertEquals(403, put(next).statusCode()); assertCounts(1, 1, 1);
    }

    @Test
    void closedSheetAndSessionArchiveRejectionsWithoutChangingResults() throws Exception {
        jdbc.update("UPDATE zaehlblatt SET status='ABGESCHLOSSEN' WHERE id=?::uuid", SHEET);
        var sheet = put(request()); assertEquals(409, sheet.statusCode()); assertError(sheet, "KONFLIKT_ARCHIVIERT_BLATT_ABGESCHLOSSEN");
        jdbc.update("UPDATE zaehlblatt SET status='OFFEN' WHERE id=?::uuid", SHEET);
        jdbc.update("UPDATE zaehlsitzung SET status='ABGESCHLOSSEN' WHERE id=?::uuid", SESSION);
        var session = put(request()); assertEquals(409, session.statusCode()); assertError(session, "KONFLIKT_ARCHIVIERT_SITZUNG_ABGESCHLOSSEN"); assertCounts(0, 0, 2);
    }

    @Test
    void invalidTargetRejectionIsNotDurableAndSameIdCanBeRechecked() throws Exception {
        var request = request(); request.put("zaehlblattId", "94000000-0000-4000-8000-000000000003");
        assertEquals(404, put(request).statusCode()); assertCounts(0, 0, 0);
        request.put("zaehlblattId", SHEET); assertEquals(200, put(request).statusCode()); assertCounts(1, 1, 1);
    }

    @Test void concurrentIdenticalRequestsCommitOnlyOnce() throws Exception { concurrent(true); }
    @Test void concurrentDifferentRequestsCannotOverwriteSameBaseRevision() throws Exception { concurrent(false); }

    private void concurrent(boolean identical) throws Exception {
        var reached = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { reached.countDown(); await(release); return call.callRealMethod(); }).when(schreibzugriff).ergaenzeHistorie(any(), any());
        var firstRequest = request(); var secondRequest = identical ? firstRequest : request();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> put(firstRequest));
            try {
                assertTrue(reached.await(10, TimeUnit.SECONDS)); assertFalse(first.isDone());
                var second = executor.submit(() -> put(secondRequest));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    if (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'", Integer.class) > 0) { waiting = true; break; }
                    Thread.sleep(20);
                }
                assertTrue(waiting); assertFalse(second.isDone()); release.countDown();
                var one = first.get(10, TimeUnit.SECONDS); var two = second.get(10, TimeUnit.SECONDS); assertEquals(200, one.statusCode());
                if (identical) { assertEquals(200, two.statusCode()); assertEquals(one.body(), two.body()); }
                else { assertEquals(409, two.statusCode()); assertError(two, "KONFLIKT_ARCHIVIERT_ERGEBNISREVISION_VERALTET"); }
                assertCounts(1, 1, identical ? 1 : 2);
            } finally { release.countDown(); }
        }
    }

    @Test
    void historyFailureReturns503AndRollsBackThenIdenticalRequestCanRetry() throws Exception {
        var request = request();
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("Demo-interne-Fehlerdetails"); }).when(schreibzugriff).ergaenzeHistorie(any(), any());
        var failed = put(request); assertEquals(503, failed.statusCode()); assertError(failed, "SPEICHERUNG_VORUEBERGEHEND_NICHT_MOEGLICH");
        assertFalse(failed.body().contains("Demo-interne-Fehlerdetails")); assertCounts(0, 0, 0);
        reset(schreibzugriff); assertEquals(200, put(request).statusCode()); assertCounts(1, 1, 1);
    }

    @Test
    void decisionAndCommitGuardErrorsNeverConfirmPartialChanges() throws Exception {
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("Demo-Entscheidungsfehler"); }).when(schreibzugriff).akzeptiereRequest(any(), anyString());
        assertEquals(503, put(request()).statusCode()); assertCounts(0, 0, 0);
        reset(schreibzugriff); doNothing().when(schreibzugriff).akzeptiereRequest(any(), anyString());
        assertEquals(503, put(request()).statusCode()); assertCounts(0, 0, 0);
    }

    @Test
    void unsupportedContentTypeReturnsGeneric415WithoutErrorRedispatchOrWrites() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + PATH))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type", "text/plain")
                    .header("Authorization", "Bearer " + keys.token())
                    .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request()))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(415, response.statusCode());
            assertError(response, "UNTERSTUETZTER_MEDIENTYP_ERFORDERLICH");
            assertCounts(0, 0, 0);
        }
    }

    static Stream<Arguments> archiveReasons() {
        return Stream.of(
                Arguments.of("basisRevision", 1L, null, "ERGEBNISREVISION_VERALTET"),
                Arguments.of("zuweisungVersion", 2L, null, "ZUWEISUNG_GEAENDERT"),
                Arguments.of("erwarteteBlattStatusVersion", 2L, null, "STATUSVERSION_VERALTET"),
                Arguments.of("erwarteteSitzungStatusVersion", 2L, null, "STATUSVERSION_VERALTET"),
                Arguments.of(null, null, "UPDATE zaehlblatt SET status='ABGESCHLOSSEN' WHERE id='" + SHEET + "'", "BLATT_ABGESCHLOSSEN"),
                Arguments.of(null, null, "UPDATE zaehlsitzung SET status='ABGESCHLOSSEN' WHERE id='" + SESSION + "'", "SITZUNG_ABGESCHLOSSEN"));
    }

    @ParameterizedTest @MethodSource("archiveReasons")
    void trustedOfflineConflictsCommitOriginalPayloadAndFinalDecision(String field, Object value, String mutation, String reason) throws Exception {
        var request = request(); if (field != null) request.put(field, value);
        if (mutation != null) jdbc.update(mutation);
        String previous = resultSnapshot();
        var response = put(request); assertEquals(409, response.statusCode()); assertError(response, "KONFLIKT_ARCHIVIERT_" + reason);
        assertArchive(request, reason, response.body());
        assertEquals(previous, resultSnapshot()); assertCounts(0, 0, 1);
        assertEquals(response.body(), put(request).body()); assertArchive(request, reason, response.body());
    }

    static Stream<Arguments> archivedQuantities() {
        return Stream.of(Arguments.of((Integer)null, "Demo: noch ungezaehlt"),
                Arguments.of(0, null), Arguments.of(21, "Demo: Stück 🟩\r\nZeile 2"));
    }
    @ParameterizedTest @MethodSource("archivedQuantities")
    void archivedOriginalQuantityAndNoteAreNotChanged(Integer quantity, String note) throws Exception {
        var request = request(); request.put("basisRevision", 1L); request.put("zaehlmenge", quantity); request.put("anmerkung", note);
        var response = put(request); assertEquals(409, response.statusCode());
        assertArchive(request, "ERGEBNISREVISION_VERALTET", response.body());
        assertEquals(quantity, jdbc.queryForObject("SELECT zaehlmenge FROM sync_konflikt", Integer.class));
        assertEquals(note, jdbc.queryForObject("SELECT anmerkung FROM sync_konflikt", String.class));
        assertCounts(0, 0, 1);
    }

    @Test
    void archivedDecisionRemainsExactAfterReopeningNewRevisionReassignmentAndRoleChange() throws Exception {
        var request = request(); request.put("basisRevision", 1L);
        var archived = put(request); assertEquals(409, archived.statusCode());
        var corrected = request(); assertEquals(200, put(corrected).statusCode());
        String snapshot = archiveSnapshot();
        assertEquals(archived.body(), put(request).body());
        jdbc.update("UPDATE zaehlerliste SET aktueller_zaehler_id='50000000-0000-4000-8000-000000000002',zuweisung_version=2 WHERE id=?::uuid", LIST);
        jdbc.update("UPDATE zaehlblatt SET status='ABGESCHLOSSEN',status_version=2 WHERE id=?::uuid", SHEET);
        jdbc.update("UPDATE zaehlsitzung SET status='ABGESCHLOSSEN',status_version=2 WHERE id=?::uuid", SESSION);
        jdbc.update("UPDATE benutzer SET rolle='ADMIN' WHERE id='50000000-0000-4000-8000-000000000001'");
        var repeated = put(request); assertEquals(409, repeated.statusCode()); assertEquals(archived.body(), repeated.body());
        assertEquals(snapshot, archiveSnapshot()); assertCounts(1, 1, 2);
        jdbc.update("UPDATE benutzer SET aktiv=false WHERE id='50000000-0000-4000-8000-000000000001'");
        assertEquals(401, put(request).statusCode()); assertEquals(snapshot, archiveSnapshot());
    }

    static Stream<Arguments> changedArchiveContent() {
        return Stream.of(Arguments.of("zaehlmenge", 99), Arguments.of("anmerkung", "Demo veraendert"),
                Arguments.of("lokaleRevision", 8L), Arguments.of("zaehlblattId", UUID.randomUUID()));
    }
    @ParameterizedTest @MethodSource("changedArchiveContent")
    void changedRequestIdReuseCannotOverwriteArchivedContent(String field, Object value) throws Exception {
        var request = request(); request.put("basisRevision", 1L);
        var first = put(request); assertEquals(409, first.statusCode()); String snapshot = archiveSnapshot();
        var changed = new LinkedHashMap<>(request); changed.put(field, value);
        var response = put(changed); assertEquals(409, response.statusCode()); assertError(response, "REQUEST_ID_REUSED");
        assertFalse(response.body().contains("KONFLIKT_ARCHIVIERT"));
        assertEquals(first.body(), put(request).body()); assertEquals(snapshot, archiveSnapshot()); assertCounts(0, 0, 1);
    }

    @Test
    void otherCounterNeverObtainsForeignArchivedDecisionOrPayload() throws Exception {
        var request = request(); request.put("basisRevision", 1L); request.put("anmerkung", "Demo-Originalnotiz");
        var archived = put(request); assertEquals(409, archived.statusCode()); String snapshot = archiveSnapshot();
        var foreign = send(PATH, mapper.writeValueAsString(request), token("demo-counter-b"));
        assertEquals(404, foreign.statusCode()); assertError(foreign, "RESSOURCE_NICHT_GEFUNDEN");
        assertFalse(foreign.body().contains("Demo-Originalnotiz")); assertFalse(foreign.body().contains("KONFLIKT_ARCHIVIERT"));
        assertEquals(snapshot, archiveSnapshot()); assertCounts(0, 0, 1);
    }

    static Stream<Arguments> invalidArchiveHierarchy() {
        return Stream.of(Arguments.of("zaehlerlisteId", "92000000-0000-4000-8000-000000000002"),
                Arguments.of("zaehlblattId", "94000000-0000-4000-8000-000000000003"),
                Arguments.of("zaehlblattId", UUID.randomUUID()));
    }
    @ParameterizedTest @MethodSource("invalidArchiveHierarchy")
    void earlyVersionFailureNeverArchivesManipulatedOrMissingTargets(String field, Object value) throws Exception {
        var request = request(); request.put("zuweisungVersion", 2L); request.put(field, value);
        var response = put(request); assertEquals(404, response.statusCode()); assertError(response, "RESSOURCE_NICHT_GEFUNDEN");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sync_konflikt", Integer.class)); assertCounts(0, 0, 0);
    }

    @Test
    void closedResourcesWithWrongPositionHierarchyAreNotArchived() throws Exception {
        jdbc.update("UPDATE zaehlsitzung SET status='ABGESCHLOSSEN' WHERE id=?::uuid", SESSION);
        var response = send(path(SESSION, "96000000-0000-4000-8000-000000000003"),
                mapper.writeValueAsString(request()), keys.token());
        assertEquals(404, response.statusCode()); assertError(response, "RESSOURCE_NICHT_GEFUNDEN");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sync_konflikt", Integer.class)); assertCounts(0, 0, 0);
    }

    @Test
    void syntaxAuthenticationRoleAndForeignAssignmentNeverProduceArchives() throws Exception {
        var request = request(); request.put("basisRevision", 1L);
        assertEquals(401, send(PATH, mapper.writeValueAsString(request), null).statusCode());
        var invalid = new LinkedHashMap<>(request); invalid.put("zaehlmenge", "0");
        assertEquals(400, put(invalid).statusCode());
        assertEquals(403, send(PATH, mapper.writeValueAsString(request), token("demo-admin")).statusCode());
        assertEquals(404, send(PATH, mapper.writeValueAsString(request), token("demo-counter-b")).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sync_konflikt", Integer.class)); assertCounts(0, 0, 0);
    }

    @Test void parallelIdenticalConflictsCreateOneArchiveAndOneDurableDecision() throws Exception { concurrentArchives(false); }
    @Test void parallelChangedContentWithSameIdCannotReplaceWinningConflict() throws Exception { concurrentArchives(true); }

    private void concurrentArchives(boolean changed) throws Exception {
        var reached = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { reached.countDown(); await(release); return call.callRealMethod(); })
                .when(schreibzugriff).lehneArchiviertenRequestAb(any(), anyString(), anyString(), any());
        var firstRequest = request(); firstRequest.put("basisRevision", 1L);
        var secondRequest = new LinkedHashMap<>(firstRequest); if (changed) secondRequest.put("zaehlmenge", 13);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> put(firstRequest));
            try {
                assertTrue(reached.await(10, TimeUnit.SECONDS)); assertFalse(first.isDone());
                var second = executor.submit(() -> put(secondRequest));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    if (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'", Integer.class) > 0) { waiting = true; break; }
                    Thread.sleep(20);
                }
                assertTrue(waiting); assertFalse(second.isDone()); release.countDown();
                var one = first.get(10, TimeUnit.SECONDS); var two = second.get(10, TimeUnit.SECONDS);
                assertEquals(409, one.statusCode()); assertEquals(409, two.statusCode());
                if (changed) assertError(two, "REQUEST_ID_REUSED"); else assertEquals(one.body(), two.body());
                assertArchive(firstRequest, "ERGEBNISREVISION_VERALTET", one.body()); assertCounts(0, 0, 1);
            } finally { release.countDown(); }
        }
    }

    @Test
    void conflictDecisionFailureRollsBackArchiveAndRequestBeforeRetry() throws Exception {
        var request = request(); request.put("basisRevision", 1L);
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("Demo-Konfliktfehler"); })
                .when(schreibzugriff).lehneArchiviertenRequestAb(any(), anyString(), anyString(), any());
        var failure = put(request); assertEquals(503, failure.statusCode()); assertError(failure, "SPEICHERUNG_VORUEBERGEHEND_NICHT_MOEGLICH");
        assertFalse(failure.body().contains("Demo-Konfliktfehler")); assertFalse(failure.body().contains("KONFLIKT_ARCHIVIERT"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sync_konflikt", Integer.class)); assertCounts(0, 0, 0);
        reset(schreibzugriff);
        var retry = put(request); assertEquals(409, retry.statusCode()); assertArchive(request, "ERGEBNISREVISION_VERALTET", retry.body());
    }

    @Test
    void missingFinalConflictDecisionTriggersCommitGuardAndFullRollback() throws Exception {
        var request = request(); request.put("basisRevision", 1L);
        doNothing().when(schreibzugriff).lehneArchiviertenRequestAb(any(), anyString(), anyString(), any());
        var failure = put(request); assertEquals(503, failure.statusCode());
        assertFalse(failure.body().contains("KONFLIKT_ARCHIVIERT"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sync_konflikt", Integer.class)); assertCounts(0, 0, 0);
    }

    private void assertArchive(Map<String, Object> request, String reason, String response) {
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM sync_konflikt", Integer.class));
        var row = jdbc.queryForMap("SELECT * FROM sync_konflikt");
        assertEquals(UUID.fromString(request.get("requestId").toString()), row.get("urspruengliche_request_id"));
        assertEquals(UUID.fromString("50000000-0000-4000-8000-000000000001"), row.get("urheber_id"));
        assertEquals(reason, row.get("konfliktcode")); assertEquals("OFFEN", row.get("status"));
        assertEquals(1, row.get("inhalt_format_version")); assertEquals(request.get("basisRevision"), row.get("basis_revision"));
        assertEquals(request.get("lokaleRevision"), row.get("lokale_revision")); assertEquals(request.get("zuweisungVersion"), row.get("zuweisung_version"));
        assertEquals(request.get("erwarteteBlattStatusVersion"), row.get("erwartete_blatt_status_version"));
        assertEquals(request.get("erwarteteSitzungStatusVersion"), row.get("erwartete_sitzung_status_version"));
        assertEquals(request.get("zaehlmenge"), row.get("zaehlmenge")); assertEquals(request.get("anmerkung"), row.get("anmerkung"));
        var content = new de.pia.inventur.backend.service.sync.ErgebnisSpeichernInhalt(1,
                de.pia.inventur.backend.entity.SyncOperation.ERGEBNIS_SPEICHERN,
                UUID.fromString(request.get("zaehlerlisteId").toString()), UUID.fromString(SESSION),
                UUID.fromString(request.get("zaehlblattId").toString()), UUID.fromString(POSITION),
                ((Number)request.get("zuweisungVersion")).longValue(), ((Number)request.get("basisRevision")).longValue(),
                ((Number)request.get("lokaleRevision")).longValue(), ((Number)request.get("erwarteteBlattStatusVersion")).longValue(),
                ((Number)request.get("erwarteteSitzungStatusVersion")).longValue(), (Integer)request.get("zaehlmenge"), (String)request.get("anmerkung"));
        var original = new de.pia.inventur.backend.service.sync.ErgebnisRequestNormalisierer().normalisiere(content);
        assertEquals(original.normalisierterInhalt(), row.get("normalisierter_inhalt"));
        assertEquals(original.inhaltFingerprint(), row.get("inhalt_fingerprint")); assertNotNull(row.get("erfasst_am"));
        var decision = jdbc.queryForMap("SELECT * FROM sync_request WHERE request_id=?::uuid", request.get("requestId").toString());
        assertEquals("ABGELEHNT", decision.get("status")); assertEquals(409, decision.get("antwort_http_status"));
        assertEquals(response, decision.get("antwort_json")); assertEquals(reason, decision.get("entscheidungscode"));
        assertNotNull(decision.get("verarbeitet_am"));
    }
    private String resultSnapshot() { return jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(e) ORDER BY id)::text FROM zaehlergebnis e", String.class); }
    private String archiveSnapshot() { return jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(k) ORDER BY id)::text FROM sync_konflikt k", String.class); }


    private LinkedHashMap<String, Object> request() {
        var body = new LinkedHashMap<String, Object>(); body.put("requestId", UUID.randomUUID());
        body.put("zaehlerlisteId", LIST); body.put("zaehlblattId", SHEET); body.put("zuweisungVersion", 1L);
        body.put("basisRevision", 0L); body.put("lokaleRevision", 7L); body.put("erwarteteBlattStatusVersion", 1L);
        body.put("erwarteteSitzungStatusVersion", 1L); body.put("zaehlmenge", 12); body.put("anmerkung", null); return body;
    }
    private HttpResponse<String> put(Map<String, Object> body) throws Exception { return send(PATH, mapper.writeValueAsString(body), keys.token()); }
    private HttpResponse<String> send(String path, String body, String token) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder().uri(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(body));
            if (token != null) request.header("Authorization", "Bearer "+token);
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    private HttpResponse<String> get(String path, String token) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder().uri(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(10)).GET();
            if (token != null) request.header("Authorization", "Bearer "+token);
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    private String token(String subject) { return keys.token(Map.of("sub", subject)); }
    private static String path(String session, String position) { return "/api/v1/sitzungen/"+session+"/positionen/"+position+"/ergebnis"; }
    private void assertCounts(int current, int history, int requests) {
        assertEquals(current, jdbc.queryForObject("SELECT count(*) FROM zaehlergebnis WHERE zaehlposition_id=?::uuid", Integer.class, POSITION));
        assertEquals(history, jdbc.queryForObject("SELECT count(*) FROM ergebnis_historie", Integer.class));
        assertEquals(requests, jdbc.queryForObject("SELECT count(*) FROM sync_request", Integer.class));
    }
    private void assertError(HttpResponse<String> result, String code) {
        var json = mapper.readTree(result.body()); assertEquals(3, json.size()); assertEquals(code, json.get("code").asString());
        assertEquals(result.statusCode(), json.get("status").asInt()); assertFalse(result.body().contains("jdbc:"));
    }
    private void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test-Timeout"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
}
