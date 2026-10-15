package de.pia.inventur.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.concurrent.*;
import java.util.stream.Stream;
import de.pia.inventur.backend.entity.*;
import de.pia.inventur.backend.repository.*;
import de.pia.inventur.backend.service.sync.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Sql(scripts="/test-demo.sql", executionPhase=Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Sql(scripts="/test-berechtigungspruefung.sql", executionPhase=Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(scripts={"/cleanup-abschluss.sql", "/cleanup-ergebnis-speichern.sql"}, executionPhase=Sql.ExecutionPhase.AFTER_TEST_METHOD)
class ZaehlblattAbschlussIntegrationTests {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("inventur_test").withUsername("inventur_test").withPassword(UUID.randomUUID().toString());
    private static final UUID USER = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("50000000-0000-4000-8000-000000000002");
    private static final UUID LIST = UUID.fromString("92000000-0000-4000-8000-000000000001");
    private static final UUID SESSION = UUID.fromString("93000000-0000-4000-8000-000000000001");
    private static final UUID SHEET = UUID.fromString("94000000-0000-4000-8000-000000000001");
    private static final UUID POSITION = UUID.fromString("96000000-0000-4000-8000-000000000001");
    @Autowired ZaehlblattAbschlussService service;
    @Autowired ErgebnisSpeichernService ergebnisse;
    @Autowired ErgebnisRequestNormalisierer normalisierer;
    @Autowired JdbcTemplate jdbc;
    @Autowired ErgebnisPruefRepository pruefDaten;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean ZaehlblattAbschlussRepository daten;
    @MockitoSpyBean ErgebnisSpeicherRepository ergebnisDaten;
    private ZaehlblattAbschlussRepository schreiber;
    private ErgebnisSpeicherRepository ergebnisSchreiber;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeEach void spies() {
        schreiber = AopTestUtils.getUltimateTargetObject(daten);
        ergebnisSchreiber = AopTestUtils.getUltimateTargetObject(ergebnisDaten);
    }

    @Test void vollstaendigesBlattMitNullmengeNullAbschlussUndGenauGespeicherterAntwort() {
        vollstaendig();
        var r = basis();
        var result = close(r);
        assertEquals("AKZEPTIERT", result.code()); assertFalse(result.wiederholung());
        var a = antwort(result);
        assertEquals(r.requestId(), a.requestId()); assertEquals(SHEET, a.zaehlblattId());
        assertEquals("AKZEPTIERT", a.entscheidung());
        assertEquals(ZaehlblattStatus.ABGESCHLOSSEN, a.blattStatus());
        assertEquals(2, a.blattStatusVersion()); assertEquals(0, a.ungezaehltePositionen());
        assertEquals(10, mapper.readTree(result.entscheidung().antwortJson()).size());
        assertEquals("ABGESCHLOSSEN", jdbc.queryForObject("SELECT status FROM zaehlblatt WHERE id=?", String.class, SHEET));
        assertEquals(2, version()); assertEquals(USER, jdbc.queryForObject("SELECT geschlossen_von FROM zaehlblatt WHERE id=?", UUID.class, SHEET));
        assertEquals(r.requestId(), jdbc.queryForObject("SELECT letzter_status_request_id FROM zaehlblatt WHERE id=?", UUID.class, SHEET));
        assertEquals(a.abgeschlossenAm(), jdbc.queryForObject("SELECT abgeschlossen_am FROM zaehlblatt WHERE id=?", OffsetDateTime.class, SHEET).toInstant());
        assertEquals(a.abgeschlossenAm(), result.entscheidung().verarbeitetAm());
        assertEquals("AKZEPTIERT", jdbc.queryForObject("SELECT status FROM sync_request WHERE request_id=?", String.class, r.requestId()));
        assertEquals("BLATT_ABSCHLIESSEN", jdbc.queryForObject("SELECT operation FROM sync_request WHERE request_id=?", String.class, r.requestId()));
        assertEquals(SHEET, jdbc.queryForObject("SELECT zaehlblatt_id FROM sync_request WHERE request_id=?", UUID.class, r.requestId()));
        assertNull(jdbc.queryForObject("SELECT zaehlposition_id FROM sync_request WHERE request_id=?", UUID.class, r.requestId()));
        assertNull(jdbc.queryForObject("SELECT erwartete_ergebnisrevision FROM sync_request WHERE request_id=?", Long.class, r.requestId()));
        assertEquals(result.entscheidung().antwortJson(), jdbc.queryForObject("SELECT antwort_json FROM sync_request WHERE request_id=?", String.class, r.requestId()));
        var normal = normalisierer.normalisiere(r);
        assertEquals(normal.normalisierterInhalt(), jdbc.queryForObject("SELECT normalisierter_inhalt FROM sync_request WHERE request_id=?", String.class, r.requestId()));
        assertEquals(normal.inhaltFingerprint(), jdbc.queryForObject("SELECT inhalt_fingerprint FROM sync_request WHERE request_id=?", String.class, r.requestId()));
        assertEquals(1, requests()); sitzungOffen();
    }

    @Test void fehlendeErgebniszeileUndNullErgebnisSindBeideUngezaehlt() {
        assertEquals(2, antwort(close(mit(basis(), "unvollstaendigBestaetigt", true))).ungezaehltePositionen());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM zaehlergebnis WHERE zaehlposition_id=?", Integer.class,
                UUID.fromString("96000000-0000-4000-8000-000000000002")));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM zaehlergebnis WHERE zaehlposition_id=?", Integer.class, POSITION));
    }
    @Test void nullIstUngezaehltUndNullmengeIstGezaehlt() {
        jdbc.update("UPDATE zaehlergebnis SET zaehlmenge=0");
        assertEquals(1, antwort(close(mit(basis(), "unvollstaendigBestaetigt", true))).ungezaehltePositionen());
    }
    @Test void ohneZusaetzlicheWarnbestaetigungKeinUnvollstaendigerAbschluss() {
        abgelehnt(close(basis()), "UNVOLLSTAENDIG_NICHT_BESTAETIGT");
    }
    @Test void keineAllgemeineBestaetigungAuchBeiVollstaendigemBlatt() {
        vollstaendig(); abgelehnt(close(mit(basis(), "abschlussBestaetigt", false)), "ABSCHLUSS_NICHT_BESTAETIGT");
    }
    @Test void keineAllgemeineBestaetigungTrotzUnvollstaendigerZustimmung() {
        var r=mit(mit(basis(), "abschlussBestaetigt", false), "unvollstaendigBestaetigt", true);
        abgelehnt(close(r), "ABSCHLUSS_NICHT_BESTAETIGT");
    }
    @Test void leerBlattDarfBewusstAbgeschlossenWerden() {
        var r=mit(basis(), "zaehlblattId", UUID.fromString("94000000-0000-4000-8000-000000000003"));
        assertEquals(0, antwort(close(r)).ungezaehltePositionen()); sitzungOffen();
    }
    @Test void alleBlaetterAbgeschlossenSchliesstAuchDieSitzung() {
        close(mit(basis(), "unvollstaendigBestaetigt", true));
        close(mit(basis(), "zaehlblattId", UUID.fromString("94000000-0000-4000-8000-000000000003")));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM zaehlblatt WHERE zaehlsitzung_id=? AND status='OFFEN'", Integer.class, SESSION));
        sitzungAbgeschlossen();
    }
    @Test void verloreneAntwortWirdAuchNachNeuzuweisungUndRollenwechselExaktWiederholt() {
        var r=mit(basis(), "unvollstaendigBestaetigt", true); var first=close(r);
        jdbc.update("UPDATE zaehlerliste SET aktueller_zaehler_id=?, zuweisung_version=2 WHERE id=?", OTHER, LIST);
        jdbc.update("UPDATE benutzer SET rolle='ADMIN' WHERE id=?", USER);
        jdbc.update("UPDATE zaehlsitzung SET status='ABGESCHLOSSEN', status_version=2 WHERE id=?", SESSION);
        var replay=close(r); assertTrue(replay.wiederholung()); assertEquals(first.entscheidung(), replay.entscheidung());
        assertEquals(2, version()); assertEquals(1, requests());
    }
    @Test void geaenderteWiederholungUndFremderUrheberErhaltenKeineAntwort() {
        var r=mit(basis(), "unvollstaendigBestaetigt", true); close(r);
        var changed=close(mit(r, "abschlussBestaetigt", false));
        assertEquals("REQUEST_ID_REUSED", changed.code()); assertNull(changed.entscheidung());
        var foreign=service.abschliessen(new GepruefterUrheber(OTHER), r);
        assertEquals("NICHT_BERECHTIGTER_ZUGRIFF", foreign.code()); assertNull(foreign.entscheidung());
        assertEquals(2, version()); assertEquals(1, requests());
    }
    @Test void neueIdNachAbschlussIstKeineWiederholung() {
        close(mit(basis(), "unvollstaendigBestaetigt", true));
        var second=close(mit(basis(), "unvollstaendigBestaetigt", true));
        assertEquals("BLATT_ABGESCHLOSSEN", second.code()); assertNull(second.entscheidung());
        assertEquals(2, version()); assertEquals(1, requests());
    }
    @Test void fremderNichtExistierenderInaktiverUndFalschGerollterBenutzer() {
        abgelehnt(service.abschliessen(new GepruefterUrheber(OTHER), basis()), "LISTE_NICHT_ZUGEWIESEN");
        abgelehnt(service.abschliessen(new GepruefterUrheber(UUID.randomUUID()), basis()), "BENUTZER_NICHT_VORHANDEN");
        jdbc.update("UPDATE benutzer SET aktiv=false WHERE id=?", USER);
        abgelehnt(close(basis()), "BENUTZER_INAKTIV");
        jdbc.update("UPDATE benutzer SET aktiv=true, rolle='ADMIN' WHERE id=?", USER);
        abgelehnt(close(basis()), "FALSCHE_ROLLE");
    }
    static Stream<Arguments> falscherKontext() {
        return Stream.of(
            Arguments.of("zaehlerlisteId", UUID.randomUUID(), "ZIEL_NICHT_VORHANDEN"),
            Arguments.of("zaehlerlisteId", UUID.fromString("92000000-0000-4000-8000-000000000002"), "LISTE_NICHT_ZUGEWIESEN"),
            Arguments.of("zaehlsitzungId", UUID.randomUUID(), "ZIEL_NICHT_VORHANDEN"),
            Arguments.of("zaehlsitzungId", UUID.fromString("93000000-0000-4000-8000-000000000002"), "FALSCHE_ZUORDNUNG"),
            Arguments.of("zaehlblattId", UUID.randomUUID(), "ZIEL_NICHT_VORHANDEN"),
            Arguments.of("zaehlblattId", UUID.fromString("94000000-0000-4000-8000-000000000002"), "FALSCHE_ZUORDNUNG"),
            Arguments.of("zuweisungVersion", 2L, "ZUWEISUNG_GEAENDERT"),
            Arguments.of("erwarteteSitzungStatusVersion", 2L, "STATUSVERSION_VERALTET"),
            Arguments.of("erwarteteBlattStatusVersion", 2L, "STATUSVERSION_VERALTET"));
    }
    @ParameterizedTest @MethodSource("falscherKontext")
    void falscherKontextOhneRegistrierung(String feld, Object wert, String code) { abgelehnt(close(mit(basis(), feld, wert)), code); }
    @Test void geschlosseneSitzung() {
        jdbc.update("UPDATE zaehlsitzung SET status='ABGESCHLOSSEN' WHERE id=?", SESSION);
        abgelehnt(close(basis()), "SITZUNG_ABGESCHLOSSEN");
    }
    @Test void geschlossenesBlatt() {
        jdbc.update("UPDATE zaehlblatt SET status='ABGESCHLOSSEN' WHERE id=?", SHEET);
        abgelehnt(close(basis()), "BLATT_ABGESCHLOSSEN");
    }
    static Stream<Arguments> ungueltig() {
        return Stream.of(Arguments.of("requestId", null), Arguments.of("formatVersion", 2),
            Arguments.of("zaehlblattId", null), Arguments.of("zuweisungVersion", -1),
            Arguments.of("erwarteteSitzungStatusVersion", 0), Arguments.of("erwarteteBlattStatusVersion", null),
            Arguments.of("abschlussBestaetigt", null), Arguments.of("unvollstaendigBestaetigt", null));
    }
    @ParameterizedTest @MethodSource("ungueltig")
    void ungueltigeRequests(String feld, Object wert) { abgelehnt(close(mit(basis(), feld, wert)), "UNGUELTIGER_REQUEST"); }

    @Test void entscheidungsfehlerRolltAuchBlattaenderungZurueckUndIdIstWiederVerwendbar() {
        var r=mit(basis(), "unvollstaendigBestaetigt", true);
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("Simulierter Fehler"); })
                .when(schreiber).akzeptiere(any(), anyString());
        assertThrows(RuntimeException.class, () -> close(r));
        assertOffen(); assertEquals(0, requests());
        reset(schreiber); assertEquals("AKZEPTIERT", close(r).code());
    }
    @Test void fehlendeEntscheidungFuehrtBeimCommitZumRollback() {
        doNothing().when(schreiber).akzeptiere(any(), anyString());
        assertThrows(IllegalStateException.class, () -> close(mit(basis(), "unvollstaendigBestaetigt", true)));
        assertOffen(); assertEquals(0, requests());
    }
    @Test void aufrufendeTransaktionIstVerboten() {
        assertThrows(IllegalTransactionStateException.class,
                () -> new TransactionTemplate(manager).execute(tx -> close(basis())));
        assertOffen();
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void paralleleAbschluesseMitGleicherOderVerschiedenerId(boolean gleicheId) throws Exception {
        var r=mit(basis(), "unvollstaendigBestaetigt", true);
        var second=gleicheId ? r : mit(r, "requestId", UUID.randomUUID());
        var reached=new CountDownLatch(1); var release=new CountDownLatch(1);
        pauseAbschluss(reached, release);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(() -> close(r)); await(reached);
            var next=pool.submit(() -> close(second));
            try { waiting(next); } finally { release.countDown(); }
            var a=first.get(15, TimeUnit.SECONDS); var b=next.get(15, TimeUnit.SECONDS);
            assertEquals("AKZEPTIERT", a.code());
            if (gleicheId) { assertTrue(b.wiederholung()); assertEquals(a.entscheidung(), b.entscheidung()); }
            else { assertEquals("BLATT_ABGESCHLOSSEN", b.code()); assertNull(b.entscheidung()); }
            assertEquals(2, version()); assertEquals(1, requests());
        } finally { release.countDown(); }
    }
    @Test void paralleleVerschiedeneBlaetterOhneSperrUpgradeDeadlock() throws Exception {
        var r=mit(basis(), "unvollstaendigBestaetigt", true);
        var second=mit(basis(), "zaehlblattId", UUID.fromString("94000000-0000-4000-8000-000000000003"));
        var reached=new CountDownLatch(1); var release=new CountDownLatch(1); pauseAbschluss(reached, release);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(() -> close(r)); await(reached);
            var next=pool.submit(() -> close(second));
            try { waiting(next); } finally { release.countDown(); }
            assertEquals("AKZEPTIERT", first.get(15, TimeUnit.SECONDS).code());
            assertEquals("AKZEPTIERT", next.get(15, TimeUnit.SECONDS).code()); assertEquals(2, requests()); sitzungAbgeschlossen();
        } finally { release.countDown(); }
    }
    @Test void ergebnisZuerstAbschlussZaehltNachDessenCommit() throws Exception {
        jdbc.update("UPDATE zaehlergebnis SET zaehlmenge=0");
        var reached=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(call -> { reached.countDown(); await(release); return call.callRealMethod(); })
                .when(ergebnisSchreiber).ergaenzeHistorie(any(), any());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var write=pool.submit(this::speichereNullmenge); await(reached);
            var finish=pool.submit(() -> close(basis()));
            try { waiting(finish); } finally { release.countDown(); }
            assertEquals("AKZEPTIERT", write.get(15, TimeUnit.SECONDS).code());
            assertEquals(0, antwort(finish.get(15, TimeUnit.SECONDS)).ungezaehltePositionen()); assertEquals(2, version());
        } finally { release.countDown(); }
    }
    @Test void abschlussZuerstVerhindertNachtraeglicheErgebnisaenderung() throws Exception {
        var reached=new CountDownLatch(1); var release=new CountDownLatch(1); pauseAbschluss(reached, release);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var finish=pool.submit(() -> close(mit(basis(), "unvollstaendigBestaetigt", true))); await(reached);
            var write=pool.submit(this::speichereNullmenge);
            try { waiting(write); } finally { release.countDown(); }
            assertEquals(2, antwort(finish.get(15, TimeUnit.SECONDS)).ungezaehltePositionen());
            assertEquals("BLATT_ABGESCHLOSSEN", write.get(15, TimeUnit.SECONDS).code());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM zaehlergebnis WHERE zaehlposition_id=?", Integer.class, POSITION));
        } finally { release.countDown(); }
    }
    @Test void neuzuweisungWartetBisAbschlussCommit() throws Exception {
        var reached=new CountDownLatch(1); var release=new CountDownLatch(1); pauseAbschluss(reached, release);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var finish=pool.submit(() -> close(mit(basis(), "unvollstaendigBestaetigt", true))); await(reached);
            var assign=pool.submit(() -> new TransactionTemplate(manager).execute(tx ->
                jdbc.update("UPDATE zaehlerliste SET aktueller_zaehler_id=?, zuweisung_version=2 WHERE id=?", OTHER, LIST)));
            try { waiting(assign); } finally { release.countDown(); }
            assertEquals("AKZEPTIERT", finish.get(15, TimeUnit.SECONDS).code());
            assertEquals(1, assign.get(15, TimeUnit.SECONDS)); assertEquals(USER, jdbc.queryForObject("SELECT geschlossen_von FROM zaehlblatt WHERE id=?", UUID.class, SHEET));
        } finally { release.countDown(); }
    }
    @Test void neuzuweisungZuerstVerhindertAbschlussDesBisherigenZaehlers() throws Exception {
        var reached=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var assign=pool.submit(() -> new TransactionTemplate(manager).execute(tx -> {
                jdbc.update("UPDATE zaehlerliste SET aktueller_zaehler_id=?, zuweisung_version=2 WHERE id=?", OTHER, LIST);
                reached.countDown(); await(release); return true;
            })); await(reached);
            var finish=pool.submit(() -> close(mit(basis(), "unvollstaendigBestaetigt", true)));
            try { waiting(finish); } finally { release.countDown(); }
            assertTrue(assign.get(15, TimeUnit.SECONDS)); abgelehnt(finish.get(15, TimeUnit.SECONDS), "LISTE_NICHT_ZUGEWIESEN");
        } finally { release.countDown(); }
    }

    @Test void globaleRequestIdIstZwischenErgebnisUndAbschlussEindeutig() {
        var r=mit(basis(), "unvollstaendigBestaetigt", true);
        assertEquals("AKZEPTIERT", ergebnisse.speichere(r.requestId(), new GepruefterUrheber(USER),
                new ErgebnisSpeichernInhalt(1, SyncOperation.ERGEBNIS_SPEICHERN, LIST, SESSION, SHEET, POSITION,
                        1L, 0L, 1L, 1L, 1L, 0, null)).code());
        abgelehnt(close(r), "REQUEST_ID_REUSED");
    }
    @Test void ergebnisRequestDarfAbschlussIdNichtWiederverwenden() {
        var r=mit(basis(), "unvollstaendigBestaetigt", true);
        close(r);
        var result=ergebnisse.speichere(r.requestId(), new GepruefterUrheber(USER),
                new ErgebnisSpeichernInhalt(1, SyncOperation.ERGEBNIS_SPEICHERN, LIST, SESSION, SHEET, POSITION,
                        1L, 0L, 1L, 1L, 1L, 0, null));
        assertEquals("REQUEST_ID_REUSED",result.code()); assertNull(result.entscheidung());
        assertEquals(1,requests()); assertEquals(2,version());
    }


    @Test void sitzungsantwortBleibtOffenSolangeEinBlattOffenIst() {
        var result=close(mit(basis(), "unvollstaendigBestaetigt", true));
        var a=antwort(result);
        assertEquals(Boolean.FALSE,a.sitzungDurchDiesenVorgangAbgeschlossen());
        assertEquals(ZaehlsitzungStatus.OFFEN,a.sitzungsStatus());
        assertEquals(1L,a.sitzungsStatusVersion());
        sitzungOffen();
    }

    @Test void letzterBlattabschlussSchliesstSitzungUndOrdnetSieDemselbenRequestZu() {
        var firstRequest=mit(basis(), "unvollstaendigBestaetigt", true);
        var first=close(firstRequest);
        var lastRequest=mit(basis(), "zaehlblattId", UUID.fromString("94000000-0000-4000-8000-000000000003"));
        var last=close(lastRequest);
        sitzungGeschlossenMitAntwort(last);
        assertEquals(2,requests());
        assertEquals(lastRequest.requestId(),jdbc.queryForObject(
                "SELECT letzter_status_request_id FROM zaehlblatt WHERE id=?",UUID.class,lastRequest.zaehlblattId()));
        assertEquals(SESSION,jdbc.queryForObject(
                "SELECT zaehlsitzung_id FROM sync_request WHERE request_id=?",UUID.class,lastRequest.requestId()));
        assertEquals(first.entscheidung(),close(firstRequest).entscheidung());
        assertEquals(ZaehlsitzungStatus.OFFEN,antwort(close(firstRequest)).sitzungsStatus());
        assertEquals(1L,antwort(close(firstRequest)).sitzungsStatusVersion());
        assertEquals(last.entscheidung(),close(lastRequest).entscheidung());
        sitzungAbgeschlossen();
    }

    @Test void einzigesVollstaendigGezaehltesBlattSchliesstSitzungOhneZweitenRequest() {
        einzelnesBlatt(); vollstaendig();
        var before=datenSnapshot();
        var r=basis();
        var result=close(r);
        sitzungGeschlossenMitAntwort(result);
        assertEquals(0,antwort(result).ungezaehltePositionen());
        assertEquals(before,datenSnapshot());
        assertEquals(1,requests());
        assertEquals(result.entscheidung().antwortJson(),jdbc.queryForObject(
                "SELECT antwort_json FROM sync_request WHERE request_id=?",String.class,r.requestId()));
    }

    @Test void leereSitzungWirdUnterEchterSperreNichtAutomatischGeschlossen() {
        new TransactionTemplate(manager).execute(tx -> {
            tx.setRollbackOnly();
            UUID empty=UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO zaehlsitzung(id,zaehlerliste_id,zaehltag,runde_nr,status,status_version,gestartet_am)
                    VALUES (?,'10000000-0000-4000-8000-000000000001','2026-10-15',1,'OFFEN',1,clock_timestamp())
                    """,empty);
            pruefDaten.sperreSitzungZumAbschluss(empty).orElseThrow();
            assertFalse(daten.schliesseSitzungFallsVollstaendig(empty,1,java.time.Instant.now()));
            assertEquals("OFFEN",jdbc.queryForObject("SELECT status FROM zaehlsitzung WHERE id=?",String.class,empty));
            assertEquals(1L,jdbc.queryForObject("SELECT status_version FROM zaehlsitzung WHERE id=?",Long.class,empty));
            assertNull(jdbc.queryForObject("SELECT abgeschlossen_am FROM zaehlsitzung WHERE id=?",OffsetDateTime.class,empty));
            return null;
        });
        assertEquals(0,requests());
    }

    @Test void unvollstaendigesEinzelblattSchliesstSitzungNurMitZusaetzlicherBestaetigung() {
        einzelnesBlatt();
        var r=basis();
        abgelehnt(close(r),"UNVOLLSTAENDIG_NICHT_BESTAETIGT");
        sitzungOffen();
        var result=close(mit(r,"unvollstaendigBestaetigt",true));
        assertEquals(2,antwort(result).ungezaehltePositionen());
        sitzungGeschlossenMitAntwort(result);
    }

    @Test void nullUndNullmengeBleibenAuchBeimSitzungsabschlussUnterschiedlich() {
        einzelnesBlatt();
        jdbc.update("UPDATE zaehlergebnis SET zaehlmenge=0");
        var result=close(mit(basis(),"unvollstaendigBestaetigt",true));
        assertEquals(1,antwort(result).ungezaehltePositionen());
        sitzungGeschlossenMitAntwort(result);
    }

    @Test void sitzungsversionSteigtVonBeliebigemAusgangswertGenauEinmalUndReplayIstIdentisch() {
        einzelnesBlatt();
        jdbc.update("UPDATE zaehlsitzung SET status_version=7 WHERE id=?",SESSION);
        var r=mit(mit(basis(),"unvollstaendigBestaetigt",true),"erwarteteSitzungStatusVersion",7L);
        var first=close(r);
        assertEquals(8L,antwort(first).sitzungsStatusVersion());
        assertEquals(8L,jdbc.queryForObject("SELECT status_version FROM zaehlsitzung WHERE id=?",Long.class,SESSION));
        String snapshot=sitzungsSnapshot();
        var replay=close(r);
        assertTrue(replay.wiederholung()); assertEquals(first.entscheidung(),replay.entscheidung());
        assertEquals(snapshot,sitzungsSnapshot()); assertEquals(1,requests()); assertEquals(2,version());
    }

    @Test void alteSiebenFeldAntwortBleibtBytegenauUndFuehrtKeineNachtraeglicheSchliessungAus() {
        einzelnesBlatt();
        var r=mit(basis(),"unvollstaendigBestaetigt",true);
        var result=close(r);
        var fields=mapper.convertValue(antwort(result),new TypeReference<LinkedHashMap<String,Object>>() {});
        fields.remove("sitzungDurchDiesenVorgangAbgeschlossen");
        fields.remove("sitzungsStatus"); fields.remove("sitzungsStatusVersion");
        String legacy=" \n"+mapper.writeValueAsString(fields)+"\n ";
        // Isoliert den Zustand aus Lernschritt 30 nachbilden, keine produktive Datenmigration.
        jdbc.update("UPDATE sync_request SET antwort_json=? WHERE request_id=?",legacy,r.requestId());
        jdbc.update("UPDATE zaehlsitzung SET status='OFFEN',status_version=1,abgeschlossen_am=NULL WHERE id=?",SESSION);
        var replay=close(r);
        assertTrue(replay.wiederholung()); assertEquals(legacy,replay.entscheidung().antwortJson());
        assertEquals(result.entscheidung().verarbeitetAm(),replay.entscheidung().verarbeitetAm());
        var old=antwort(replay);
        assertNull(old.sitzungDurchDiesenVorgangAbgeschlossen());
        assertNull(old.sitzungsStatus()); assertNull(old.sitzungsStatusVersion());
        sitzungOffen(); assertEquals(2,version()); assertEquals(1,requests());
        var normalized=normalisierer.normalisiere(r);
        assertEquals(normalized.inhaltFingerprint(),jdbc.queryForObject(
                "SELECT inhalt_fingerprint FROM sync_request WHERE request_id=?",String.class,r.requestId()));
    }

    @Test void paralleleLetzteBlaetterSchliessenDieSitzungGenauEinmal() throws Exception {
        var firstRequest=mit(basis(),"unvollstaendigBestaetigt",true);
        var lastRequest=mit(basis(),"zaehlblattId",UUID.fromString("94000000-0000-4000-8000-000000000003"));
        var reached=new CountDownLatch(1); var release=new CountDownLatch(1); pauseAbschluss(reached,release);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(() -> close(firstRequest)); await(reached);
            var last=pool.submit(() -> close(lastRequest));
            try { waiting(last); } finally { release.countDown(); }
            var a=first.get(15,TimeUnit.SECONDS); var b=last.get(15,TimeUnit.SECONDS);
            assertEquals(Boolean.FALSE,antwort(a).sitzungDurchDiesenVorgangAbgeschlossen());
            assertEquals(ZaehlsitzungStatus.OFFEN,antwort(a).sitzungsStatus());
            sitzungGeschlossenMitAntwort(b); assertEquals(2,requests());
            assertEquals(a.entscheidung(),close(firstRequest).entscheidung());
            assertEquals(b.entscheidung(),close(lastRequest).entscheidung()); sitzungAbgeschlossen();
        } finally { release.countDown(); }
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void paralleleAnfragenFuerDasselbeLetzteBlatt(boolean gleicheId) throws Exception {
        einzelnesBlatt();
        var r=mit(basis(),"unvollstaendigBestaetigt",true);
        var second=gleicheId ? r : mit(r,"requestId",UUID.randomUUID());
        var reached=new CountDownLatch(1); var release=new CountDownLatch(1); pauseAbschluss(reached,release);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(() -> close(r)); await(reached);
            var next=pool.submit(() -> close(second));
            try { waiting(next); } finally { release.countDown(); }
            var a=first.get(15,TimeUnit.SECONDS); var b=next.get(15,TimeUnit.SECONDS);
            sitzungGeschlossenMitAntwort(a);
            if(gleicheId) { assertTrue(b.wiederholung()); assertEquals(a.entscheidung(),b.entscheidung()); }
            else { assertEquals("SITZUNG_ABGESCHLOSSEN",b.code()); assertNull(b.entscheidung()); }
            assertEquals(1,requests()); assertEquals(2,version()); sitzungAbgeschlossen();
        } finally { release.countDown(); }
    }

    @Test void ergebnisCommitVorLetztemAbschlussWirdMitgezaehltUndHistorieBleibtErhalten() throws Exception {
        einzelnesBlatt(); jdbc.update("UPDATE zaehlergebnis SET zaehlmenge=0");
        var reached=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(call -> { reached.countDown(); await(release); return call.callRealMethod(); })
                .when(ergebnisSchreiber).ergaenzeHistorie(any(),any());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var write=pool.submit(this::speichereNullmenge); await(reached);
            var finish=pool.submit(() -> close(basis()));
            try { waiting(finish); } finally { release.countDown(); }
            assertEquals("AKZEPTIERT",write.get(15,TimeUnit.SECONDS).code());
            var result=finish.get(15,TimeUnit.SECONDS);
            sitzungGeschlossenMitAntwort(result); assertEquals(0,antwort(result).ungezaehltePositionen());
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM ergebnis_historie WHERE zaehlposition_id=? AND zaehlmenge=0 AND revision=1",Integer.class,POSITION));
            assertEquals(0,jdbc.queryForObject("SELECT zaehlmenge FROM zaehlergebnis WHERE zaehlposition_id=?",Integer.class,POSITION));
        } finally { release.countDown(); }
    }

    @Test void letzterAbschlussVorErgebnisaenderungLehntMengeMitGeschlossenerSitzungAb() throws Exception {
        einzelnesBlatt();
        String before=datenSnapshot();
        var reached=new CountDownLatch(1); var release=new CountDownLatch(1); pauseAbschluss(reached,release);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var finish=pool.submit(() -> close(mit(basis(),"unvollstaendigBestaetigt",true))); await(reached);
            var write=pool.submit(this::speichereNullmenge);
            try { waiting(write); } finally { release.countDown(); }
            sitzungGeschlossenMitAntwort(finish.get(15,TimeUnit.SECONDS));
            assertEquals("SITZUNG_ABGESCHLOSSEN",write.get(15,TimeUnit.SECONDS).code());
            assertEquals(before,datenSnapshot()); assertEquals(1,requests());
        } finally { release.countDown(); }
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void neuzuweisungUndLetzterAbschlussSindGeordnet(boolean abschlussZuerst) throws Exception {
        einzelnesBlatt();
        var reached=new CountDownLatch(1); var release=new CountDownLatch(1);
        if(abschlussZuerst) pauseAbschluss(reached,release);
        try(var pool=Executors.newFixedThreadPool(2)) {
            if(abschlussZuerst) {
                var finish=pool.submit(() -> close(mit(basis(),"unvollstaendigBestaetigt",true))); await(reached);
                var assign=pool.submit(() -> new TransactionTemplate(manager).execute(tx ->
                        jdbc.update("UPDATE zaehlerliste SET aktueller_zaehler_id=?,zuweisung_version=2 WHERE id=?",OTHER,LIST)));
                try { waiting(assign); } finally { release.countDown(); }
                sitzungGeschlossenMitAntwort(finish.get(15,TimeUnit.SECONDS));
                assertEquals(1,assign.get(15,TimeUnit.SECONDS));
                assertEquals(USER,jdbc.queryForObject("SELECT geschlossen_von FROM zaehlblatt WHERE id=?",UUID.class,SHEET));
            } else {
                var assign=pool.submit(() -> new TransactionTemplate(manager).execute(tx -> {
                    jdbc.update("UPDATE zaehlerliste SET aktueller_zaehler_id=?,zuweisung_version=2 WHERE id=?",OTHER,LIST);
                    reached.countDown(); await(release); return true;
                })); await(reached);
                var finish=pool.submit(() -> close(mit(basis(),"unvollstaendigBestaetigt",true)));
                try { waiting(finish); } finally { release.countDown(); }
                assertTrue(assign.get(15,TimeUnit.SECONDS)); abgelehnt(finish.get(15,TimeUnit.SECONDS),"LISTE_NICHT_ZUGEWIESEN");
                assertOffen(); sitzungOffen();
            }
        } finally { release.countDown(); }
    }

    @ParameterizedTest @ValueSource(strings={"SITZUNG","ENTSCHEIDUNG","COMMIT"})
    void fehlerNachSitzungswechselRolltBlattSitzungUndRequestGemeinsamZurueck(String phase) {
        einzelnesBlatt(); vollstaendig(); String before=datenSnapshot();
        var r=basis();
        if(phase.equals("SITZUNG"))
            doAnswer(call -> { assertEquals(true,call.callRealMethod()); throw new IllegalStateException("Simulierter Sitzungsfehler"); })
                    .when(schreiber).schliesseSitzungFallsVollstaendig(any(),anyLong(),any());
        else if(phase.equals("ENTSCHEIDUNG"))
            doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("Simulierter Entscheidungsfehler"); })
                    .when(schreiber).akzeptiere(any(),anyString());
        else doNothing().when(schreiber).akzeptiere(any(),anyString());
        assertThrows(RuntimeException.class,() -> close(r));
        assertOffen(); sitzungOffen(); assertEquals(0,requests()); assertEquals(before,datenSnapshot());
        reset(schreiber);
        sitzungGeschlossenMitAntwort(close(r)); assertEquals(before,datenSnapshot());
    }

    @Test void ueberlaufDerSitzungsversionRolltDenLetztenBlattabschlussZurueck() {
        einzelnesBlatt();
        jdbc.update("UPDATE zaehlsitzung SET status_version=? WHERE id=?",Long.MAX_VALUE,SESSION);
        var r=mit(mit(basis(),"unvollstaendigBestaetigt",true),"erwarteteSitzungStatusVersion",Long.MAX_VALUE);
        assertThrows(RuntimeException.class,() -> close(r));
        assertOffen(); assertEquals(0,requests());
        assertEquals("OFFEN",jdbc.queryForObject("SELECT status FROM zaehlsitzung WHERE id=?",String.class,SESSION));
        assertEquals(Long.MAX_VALUE,jdbc.queryForObject("SELECT status_version FROM zaehlsitzung WHERE id=?",Long.class,SESSION));
        assertNull(jdbc.queryForObject("SELECT abgeschlossen_am FROM zaehlsitzung WHERE id=?",OffsetDateTime.class,SESSION));
    }

    private void einzelnesBlatt() {
        jdbc.update("DELETE FROM zaehlblatt WHERE id='94000000-0000-4000-8000-000000000003'");
    }
    private void sitzungGeschlossenMitAntwort(ErgebnisSpeicherergebnis result) {
        var a=antwort(result); sitzungAbgeschlossen();
        assertEquals(Boolean.TRUE,a.sitzungDurchDiesenVorgangAbgeschlossen());
        assertEquals(ZaehlsitzungStatus.ABGESCHLOSSEN,a.sitzungsStatus());
        assertEquals(2L,a.sitzungsStatusVersion());
        assertEquals(a.abgeschlossenAm(),jdbc.queryForObject("SELECT abgeschlossen_am FROM zaehlsitzung WHERE id=?",OffsetDateTime.class,SESSION).toInstant());
    }
    private String datenSnapshot() {
        return jdbc.queryForObject("""
                SELECT json_build_object('ergebnisse',(SELECT json_agg(e ORDER BY id) FROM zaehlergebnis e),
                    'historie',(SELECT json_agg(h ORDER BY id) FROM ergebnis_historie h))::text
                """,String.class);
    }
    private String sitzungsSnapshot() {
        return jdbc.queryForObject("SELECT row_to_json(s)::text FROM zaehlsitzung s WHERE id=?",String.class,SESSION);
    }

    private void vollstaendig() {
        jdbc.update("UPDATE zaehlergebnis SET zaehlmenge=0");
        assertEquals("AKZEPTIERT", speichereNullmenge().code());
        // Bewusstes Bestreben: vollstaendig gezaehlt ist noch kein automatischer Abschluss.
        assertOffen();
    }
    private ErgebnisSpeicherergebnis speichereNullmenge() {
        return ergebnisse.speichere(UUID.randomUUID(), new GepruefterUrheber(USER),
                new ErgebnisSpeichernInhalt(1, SyncOperation.ERGEBNIS_SPEICHERN, LIST, SESSION, SHEET, POSITION,
                        1L, 0L, 1L, 1L, 1L, 0, null));
    }
    private void pauseAbschluss(CountDownLatch reached, CountDownLatch release) {
        doAnswer(call -> { reached.countDown(); await(release); return call.callRealMethod(); })
                .when(schreiber).akzeptiere(any(), anyString());
    }
    private void waiting(Future<?> task) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8); boolean locked=false;
        while(System.nanoTime()<deadline) {
            if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'", Integer.class)>0) {
                locked=true; break;
            }
            Thread.sleep(20);
        }
        assertTrue(locked, "Konkurrent muss in echter PostgreSQL-Transaktion auf Sperre warten");
        assertFalse(task.isDone());
    }
    private void await(CountDownLatch latch) {
        try { if(!latch.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Test-Timeout"); }
        catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
    }
    private void abgelehnt(ErgebnisSpeicherergebnis r, String code) {
        assertEquals(code,r.code()); assertNull(r.entscheidung()); assertFalse(r.wiederholung());
        assertEquals(0,requests()); assertEquals(1,version());
    }
    private void assertOffen() {
        assertEquals("OFFEN",jdbc.queryForObject("SELECT status FROM zaehlblatt WHERE id=?",String.class,SHEET));
        assertEquals(1,version());
        assertNull(jdbc.queryForObject("SELECT letzter_status_request_id FROM zaehlblatt WHERE id=?",UUID.class,SHEET));
        assertNull(jdbc.queryForObject("SELECT geschlossen_von FROM zaehlblatt WHERE id=?",UUID.class,SHEET));
        assertNull(jdbc.queryForObject("SELECT abgeschlossen_am FROM zaehlblatt WHERE id=?",OffsetDateTime.class,SHEET));
    }
    private void sitzungOffen() {
        assertEquals("OFFEN",jdbc.queryForObject("SELECT status FROM zaehlsitzung WHERE id=?",String.class,SESSION));
        assertEquals(1,jdbc.queryForObject("SELECT status_version FROM zaehlsitzung WHERE id=?",Long.class,SESSION));
        assertNull(jdbc.queryForObject("SELECT abgeschlossen_am FROM zaehlsitzung WHERE id=?",OffsetDateTime.class,SESSION));
    }
    private void sitzungAbgeschlossen() {
        assertEquals("ABGESCHLOSSEN",jdbc.queryForObject("SELECT status FROM zaehlsitzung WHERE id=?",String.class,SESSION));
        assertEquals(2,jdbc.queryForObject("SELECT status_version FROM zaehlsitzung WHERE id=?",Long.class,SESSION));
        assertNotNull(jdbc.queryForObject("SELECT abgeschlossen_am FROM zaehlsitzung WHERE id=?",OffsetDateTime.class,SESSION));
    }
    private long version() { return jdbc.queryForObject("SELECT status_version FROM zaehlblatt WHERE id=?",Long.class,SHEET); }
    private int requests() { return jdbc.queryForObject("SELECT count(*) FROM sync_request WHERE operation='BLATT_ABSCHLIESSEN'",Integer.class); }
    private ZaehlblattAbschlussAntwort antwort(ErgebnisSpeicherergebnis r) {
        assertEquals("AKZEPTIERT",r.code()); return mapper.readValue(r.entscheidung().antwortJson(),ZaehlblattAbschlussAntwort.class);
    }
    private ErgebnisSpeicherergebnis close(ZaehlblattAbschlussRequest r) { return service.abschliessen(new GepruefterUrheber(USER),r); }
    private ZaehlblattAbschlussRequest basis() {
        return new ZaehlblattAbschlussRequest(UUID.randomUUID(),1,LIST,SESSION,SHEET,1L,1L,1L,true,false);
    }
    private ZaehlblattAbschlussRequest mit(ZaehlblattAbschlussRequest r,String feld,Object wert) {
        var fields=mapper.convertValue(r,new TypeReference<LinkedHashMap<String,Object>>() {});
        fields.put(feld,wert); return mapper.convertValue(fields,ZaehlblattAbschlussRequest.class);
    }
}
