package de.pia.inventur.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import de.pia.inventur.backend.repository.ZaehlblattAbschlussRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ZaehlblattAbschlussHttpIntegrationTests.TestDecoder.class)
@Sql(scripts="/test-demo.sql", executionPhase=Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Sql(scripts="/test-berechtigungspruefung.sql", executionPhase=Sql.ExecutionPhase.BEFORE_TEST_METHOD)
@Sql(scripts={"/cleanup-abschluss.sql","/cleanup-ergebnis-speichern.sql"}, executionPhase=Sql.ExecutionPhase.AFTER_TEST_METHOD)
class ZaehlblattAbschlussHttpIntegrationTests {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("inventur_test").withUsername("inventur_test").withPassword(UUID.randomUUID().toString());
    private static final JwtTestSchluessel keys = new JwtTestSchluessel();
    private static final String USER="50000000-0000-4000-8000-000000000001";
    private static final String LIST="92000000-0000-4000-8000-000000000001";
    private static final String SESSION="93000000-0000-4000-8000-000000000001";
    private static final String SHEET="94000000-0000-4000-8000-000000000001";
    private static final String SECOND="94000000-0000-4000-8000-000000000003";
    private static final String POSITION="96000000-0000-4000-8000-000000000001";
    private static final String PATH=path(SESSION,SHEET);
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean ZaehlblattAbschlussRepository daten;
    private ZaehlblattAbschlussRepository schreiber;
    private final JsonMapper mapper=JsonMapper.builder().build();

    @TestConfiguration(proxyBeanMethods=false)
    static class TestDecoder {
        @Bean @Primary JwtDecoder isolierterTestDecoder() { return keys.decoder(); }
    }
    @BeforeEach void identitaeten() {
        schreiber=AopTestUtils.getUltimateTargetObject(daten);
        jdbc.update("UPDATE benutzer SET aktiv=true,rolle='ZAEHLER' WHERE id IN ('50000000-0000-4000-8000-000000000001','50000000-0000-4000-8000-000000000002')");
        jdbc.update("UPDATE benutzer SET oidc_issuer=?,oidc_subject=? WHERE id=?::uuid",JwtTestSchluessel.ISSUER,JwtTestSchluessel.SUBJECT,USER);
        jdbc.update("UPDATE benutzer SET oidc_issuer=?,oidc_subject='demo-counter-b' WHERE id='50000000-0000-4000-8000-000000000002'",JwtTestSchluessel.ISSUER);
        jdbc.update("UPDATE benutzer SET oidc_issuer=?,oidc_subject='demo-admin' WHERE id='50000000-0000-4000-8000-000000000003'",JwtTestSchluessel.ISSUER);
    }

    @Test void vollstaendigesBlattSpeichertIdentitaetAntwortUndErhaeltErgebnisseUndHistorie() throws Exception {
        assertEquals("inventur_test",jdbc.queryForObject("SELECT current_database()",String.class));
        zaehleErstePosition(0); jdbc.update("UPDATE zaehlergebnis SET zaehlmenge=0 WHERE zaehlposition_id<>?::uuid",POSITION);
        String before=datenSnapshot();
        var r=request(); r.put("unvollstaendigBestaetigt",false);
        var response=put(r); var json=success(response,r);
        assertEquals(0,json.get("ungezaehltePositionen").asLong());
        assertEquals("OFFEN",json.get("sitzungsStatus").asString());
        assertFalse(json.get("sitzungDurchDiesenVorgangAbgeschlossen").asBoolean());
        assertEquals(1,json.get("sitzungsStatusVersion").asLong());
        assertEquals(USER,jdbc.queryForObject("SELECT geschlossen_von::text FROM zaehlblatt WHERE id=?::uuid",String.class,SHEET));
        assertEquals(r.get("requestId"),jdbc.queryForObject("SELECT letzter_status_request_id FROM zaehlblatt WHERE id=?::uuid",UUID.class,SHEET));
        assertEquals(json.get("abgeschlossenAm").asString(),jdbc.queryForObject("SELECT abgeschlossen_am FROM zaehlblatt WHERE id=?::uuid",OffsetDateTime.class,SHEET).toInstant().toString());
        assertEquals(before,datenSnapshot()); assertEquals(1,requests());
        assertArrayEquals(stored(r),response.body());
    }

    @Test void bestaetigtesUnvollstaendigesBlattZaehltNullUndFehlendeErgebniszeile() throws Exception {
        var r=request(); var json=success(put(r),r);
        assertEquals(2,json.get("ungezaehltePositionen").asLong());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM zaehlergebnis",Integer.class));
    }
    @Test void nullmengeIstGezaehltFehlendeZeileBleibtUngezaehlt() throws Exception {
        jdbc.update("UPDATE zaehlergebnis SET zaehlmenge=0");
        var r=request(); assertEquals(1,success(put(r),r).get("ungezaehltePositionen").asLong());
    }
    @Test void gespeicherteNullMengeBleibtUngezaehlt() throws Exception {
        zaehleErstePosition(null);
        var r=request(); assertEquals(2,success(put(r),r).get("ungezaehltePositionen").asLong());
    }

    @ParameterizedTest @CsvSource({"abschlussBestaetigt,ABSCHLUSS_NICHT_BESTAETIGT","unvollstaendigBestaetigt,UNVOLLSTAENDIG_NICHT_BESTAETIGT"})
    void fehlendeBewussteZustimmungIstFachlicherKonflikt(String feld,String code) throws Exception {
        var r=request(); r.put(feld,false); String before=statusSnapshot();
        error(put(r),409,code,"Abschlussrequest konnte wegen eines Konflikts nicht uebernommen werden.");
        assertEquals(before,statusSnapshot()); assertEquals(0,requests());
    }
    @Test void vollstaendigkeitErsetztDieAllgemeineBestaetigungNicht() throws Exception {
        zaehleErstePosition(0); jdbc.update("UPDATE zaehlergebnis SET zaehlmenge=0");
        var r=request(); r.put("abschlussBestaetigt",false);
        error(put(r),409,"ABSCHLUSS_NICHT_BESTAETIGT",null); assertEquals(0,requests());
    }

    static Stream<Arguments> invalidFields() {
        return Stream.of(Arguments.of("requestId",null),Arguments.of("requestId","keine-uuid"),
                Arguments.of("requestId","1-1-1-1-1"),Arguments.of("requestId",42),
                Arguments.of("zaehlerlisteId",null),Arguments.of("zaehlerlisteId",false),
                Arguments.of("zaehlerlisteId","kein-uuid"),Arguments.of("zuweisungVersion",-1),
                Arguments.of("zuweisungVersion","1"),Arguments.of("zuweisungVersion",true),
                Arguments.of("zuweisungVersion",1.5),Arguments.of("erwarteteSitzungStatusVersion",0),
                Arguments.of("erwarteteSitzungStatusVersion",-1),Arguments.of("erwarteteSitzungStatusVersion",null),
                Arguments.of("erwarteteBlattStatusVersion",0),Arguments.of("erwarteteBlattStatusVersion",-1),
                Arguments.of("erwarteteBlattStatusVersion",false),Arguments.of("erwarteteBlattStatusVersion","1"),
                Arguments.of("abschlussBestaetigt",null),Arguments.of("abschlussBestaetigt","true"),
                Arguments.of("abschlussBestaetigt",1),Arguments.of("unvollstaendigBestaetigt",null),
                Arguments.of("unvollstaendigBestaetigt","false"),Arguments.of("unvollstaendigBestaetigt",0),
                Arguments.of("unvollstaendigBestaetigt",Map.of()),Arguments.of("benutzerId",USER),
                Arguments.of("benutzername","Demo-Zaehler"),Arguments.of("zaehlblattId",SHEET),
                Arguments.of("zaehlsitzungId",SESSION),Arguments.of("formatVersion",1));
    }
    @ParameterizedTest @MethodSource("invalidFields")
    void ungueltigeTypenUndZusaetzlicheFelderWerdenNichtUebernommen(String feld,Object wert) throws Exception {
        var r=request(); r.put(feld,wert); String before=statusSnapshot();
        error(put(r),400,"UNGUELTIGER_REQUEST","Abschlussrequest ist ungueltig.");
        assertEquals(before,statusSnapshot()); assertEquals(0,requests());
    }
    @ParameterizedTest @ValueSource(strings={"requestId","zaehlerlisteId","zuweisungVersion","erwarteteSitzungStatusVersion","erwarteteBlattStatusVersion","abschlussBestaetigt","unvollstaendigBestaetigt"})
    void alleSiebenFelderSindPflicht(String feld) throws Exception {
        var r=request(); r.remove(feld);
        error(put(r),400,"UNGUELTIGER_REQUEST",null); assertEquals(0,requests());
    }
    @Test void doppelteFelderUeberlaufUndTrailingJsonSindUngueltig() throws Exception {
        String valid=mapper.writeValueAsString(request());
        for(String invalid:new String[]{"","null","[]","{",valid+" {}",valid.substring(0,valid.length()-1)+",\"abschlussBestaetigt\":true}",
                valid.replace("\"zuweisungVersion\":1","\"zuweisungVersion\":9223372036854775808"),
                valid.replace("\"abschlussBestaetigt\":true","\"benutzerId\":\""+USER+"\"")}) {
            error(send(PATH,invalid,keys.token()),400,"UNGUELTIGER_REQUEST",null);
        }
        assertEquals(0,requests());
    }
    @ParameterizedTest @ValueSource(strings={"keine-uuid","1-1-1-1-1"})
    void pfadUuidsWerdenStriktValidiert(String value) throws Exception {
        var body=mapper.writeValueAsString(request());
        error(send(path(value,SHEET),body,keys.token()),400,"UNGUELTIGER_REQUEST",null);
        error(send(path(SESSION,value),body,keys.token()),400,"UNGUELTIGER_REQUEST",null);
        assertEquals(0,requests());
    }

    @Test void authentifizierungIstErforderlichUndIdentitaetsheaderHilftNicht() throws Exception {
        var result=send(PATH,mapper.writeValueAsString(request()),null);
        error(result,401,"AUTHENTIFIZIERUNG_ERFORDERLICH","Eine gueltige Anmeldung ist erforderlich.");
        assertEquals("Bearer",result.headers().firstValue("WWW-Authenticate").orElseThrow()); assertEquals(0,requests());
    }
    @Test void ungueltigeJwtSignaturIssuerAudienceUndAblaufWerdenAbgewiesen() throws Exception {
        var expired=java.util.Date.from(java.time.Instant.now().minusSeconds(3600));
        for(String token:new String[]{"ungueltiges-demo-token",new JwtTestSchluessel().token(),
                keys.token(Map.of("iss","https://fremder-demo-issuer.example")),keys.token(Map.of("aud","andere-demo-api")),
                keys.token(Map.of("exp",expired))}) {
            var result=send(PATH,mapper.writeValueAsString(request()),token);
            error(result,401,"AUTHENTIFIZIERUNG_ERFORDERLICH","Eine gueltige Anmeldung ist erforderlich.");
            assertEquals("Bearer",result.headers().firstValue("WWW-Authenticate").orElseThrow());
        }
        assertEquals(0,requests());
    }
    @Test void inaktiverUndUnbekannterBenutzerWerdenBereitsBeiJwtZuordnungAbgewiesen() throws Exception {
        String body=mapper.writeValueAsString(request());
        error(send(PATH,body,token("demo-unbekannt")),401,"AUTHENTIFIZIERUNG_ERFORDERLICH",null);
        jdbc.update("UPDATE benutzer SET aktiv=false WHERE id=?::uuid",USER);
        error(send(PATH,body,keys.token()),401,"AUTHENTIFIZIERUNG_ERFORDERLICH",null); assertEquals(0,requests());
    }
    @Test void adminDarfKeinenNeuenAbschlussAusloesen() throws Exception {
        error(send(PATH,mapper.writeValueAsString(request()),token("demo-admin")),403,"ZUGRIFF_VERWEIGERT","Die erforderliche Berechtigung fehlt.");
        assertEquals(0,requests());
    }
    @Test void fremdeUnbekannteUndFalschZugeordneteZieleHabenDieselbe404Antwort() throws Exception {
        String body=mapper.writeValueAsString(request());
        var foreign=send(PATH,body,token("demo-counter-b"));
        error(foreign,404,"RESSOURCE_NICHT_GEFUNDEN","Ressource nicht gefunden.");
        for(String target:new String[]{path(SESSION,UUID.randomUUID().toString()),
                path(UUID.randomUUID().toString(),SHEET),
                path("93000000-0000-4000-8000-000000000002",SHEET),
                path(SESSION,"94000000-0000-4000-8000-000000000002")}) {
            var response=send(target,body,keys.token()); assertEquals(404,response.statusCode()); assertArrayEquals(foreign.body(),response.body());
        }
        var r=request(); r.put("zaehlerlisteId","92000000-0000-4000-8000-000000000002");
        assertArrayEquals(foreign.body(),put(r).body());
        r.put("zaehlerlisteId",UUID.randomUUID());
        assertArrayEquals(foreign.body(),put(r).body()); assertEquals(0,requests());
    }
    @ParameterizedTest @CsvSource({"zuweisungVersion,ZUWEISUNG_GEAENDERT","erwarteteBlattStatusVersion,STATUSVERSION_VERALTET","erwarteteSitzungStatusVersion,STATUSVERSION_VERALTET"})
    void veralteteVersionenSind409OhneRegistrierungOderArchiv(String feld,String code) throws Exception {
        var r=request(); r.put(feld,2L); String before=statusSnapshot();
        error(put(r),409,code,null); assertEquals(before,statusSnapshot()); assertEquals(0,requests());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM sync_konflikt",Integer.class));
    }
    @ParameterizedTest @CsvSource({"zaehlblatt,BLATT_ABGESCHLOSSEN","zaehlsitzung,SITZUNG_ABGESCHLOSSEN"})
    void bereitsGeschlosseneZieleSind409(String tabelle,String code) throws Exception {
        if(tabelle.equals("zaehlblatt")) jdbc.update("UPDATE zaehlblatt SET status='ABGESCHLOSSEN' WHERE id=?::uuid",SHEET);
        else jdbc.update("UPDATE zaehlsitzung SET status='ABGESCHLOSSEN' WHERE id=?::uuid",SESSION);
        String before=statusSnapshot(); error(put(request()),409,code,null);
        assertEquals(before,statusSnapshot()); assertEquals(0,requests());
    }

    @Test void nurLetztesBlattSchliesstDieSitzungUndFrueheresReplayBleibtUnveraendert() throws Exception {
        var firstRequest=request(); var first=put(firstRequest);
        assertEquals("OFFEN",success(first,firstRequest).get("sitzungsStatus").asString());
        var lastRequest=request(); lastRequest.put("unvollstaendigBestaetigt",false);
        var last=send(path(SESSION,SECOND),mapper.writeValueAsString(lastRequest),keys.token());
        assertEquals(200,last.statusCode()); var json=mapper.readTree(last.body());
        assertEquals(SECOND,json.get("zaehlblattId").asString());
        assertEquals("ABGESCHLOSSEN",json.get("sitzungsStatus").asString());
        assertTrue(json.get("sitzungDurchDiesenVorgangAbgeschlossen").asBoolean());
        assertEquals(2,json.get("sitzungsStatusVersion").asLong());
        String after=statusSnapshot();
        assertArrayEquals(first.body(),put(firstRequest).body());
        assertArrayEquals(last.body(),send(path(SESSION,SECOND),mapper.writeValueAsString(lastRequest),keys.token()).body());
        assertEquals(after,statusSnapshot()); assertEquals(2,requests());
    }
    @Test void letztesUnvollstaendigesBlattSchliesstSitzungMitBestaetigungUndReplayAendertNichts() throws Exception {
        einzelnesBlatt(); var r=request(); var first=put(r); var json=success(first,r);
        assertTrue(json.get("sitzungDurchDiesenVorgangAbgeschlossen").asBoolean());
        assertEquals("ABGESCHLOSSEN",json.get("sitzungsStatus").asString());
        assertEquals(2,json.get("sitzungsStatusVersion").asLong());
        assertEquals(2,json.get("ungezaehltePositionen").asLong());
        assertEquals(json.get("abgeschlossenAm").asString(),jdbc.queryForObject("SELECT abgeschlossen_am FROM zaehlsitzung WHERE id=?::uuid",OffsetDateTime.class,SESSION).toInstant().toString());
        String after=statusSnapshot();
        for(int i=0;i<3;i++) {
            var replay=put(r); assertEquals(200,replay.statusCode()); assertArrayEquals(first.body(),replay.body());
            assertArrayEquals(stored(r),replay.body());
        }
        assertEquals(after,statusSnapshot()); assertEquals(1,requests());
    }
    @Test void replayNachNeuzuweisungUndRollenwechselBleibtUrhebergebunden() throws Exception {
        var r=request(); var first=put(r); success(first,r);
        jdbc.update("UPDATE zaehlerliste SET aktueller_zaehler_id='50000000-0000-4000-8000-000000000002',zuweisung_version=2 WHERE id=?::uuid",LIST);
        jdbc.update("UPDATE benutzer SET rolle='ADMIN' WHERE id=?::uuid",USER);
        String after=statusSnapshot();
        var replay=put(r); assertEquals(200,replay.statusCode()); assertArrayEquals(first.body(),replay.body());
        error(put(request()),403,"ZUGRIFF_VERWEIGERT",null);
        error(send(PATH,mapper.writeValueAsString(r),token("demo-counter-b")),404,"RESSOURCE_NICHT_GEFUNDEN",null);
        jdbc.update("UPDATE benutzer SET aktiv=false WHERE id=?::uuid",USER);
        error(put(r),401,"AUTHENTIFIZIERUNG_ERFORDERLICH",null);
        assertEquals(after,statusSnapshot()); assertEquals(1,requests());
    }
    @ParameterizedTest @CsvSource({"abschlussBestaetigt,false","unvollstaendigBestaetigt,false"})
    void dieselbeRequestIdMitGeaendertemInhaltIst409(String feld,boolean wert) throws Exception {
        var r=request(); var accepted=put(r); success(accepted,r);
        var changed=new LinkedHashMap<>(r); changed.put(feld,wert);
        error(put(changed),409,"REQUEST_ID_REUSED",null);
        assertArrayEquals(accepted.body(),put(r).body()); assertEquals(1,requests());
    }
    @Test void pfadIstTeilDesRequestinhaltsUndDarfBeiReplayNichtAusgetauschtWerden() throws Exception {
        var r=request(); success(put(r),r);
        error(send(path(SESSION,SECOND),mapper.writeValueAsString(r),keys.token()),409,"REQUEST_ID_REUSED",null);
        assertEquals(1,requests());
    }
    @Test void fremdeRequestIdErhaeltKeineAntwortAuchNachZuweisungAnFremdenBenutzer() throws Exception {
        var r=request(); success(put(r),r);
        jdbc.update("UPDATE zaehlerliste SET aktueller_zaehler_id='50000000-0000-4000-8000-000000000002',zuweisung_version=2 WHERE id=?::uuid",LIST);
        var foreign=send(PATH,mapper.writeValueAsString(r),token("demo-counter-b"));
        error(foreign,404,"RESSOURCE_NICHT_GEFUNDEN",null);
        assertFalse(text(foreign).contains(r.get("requestId").toString()));
        assertFalse(text(foreign).contains("ungezaehltePositionen")); assertEquals(1,requests());
    }
    @Test void auchAlteAntwortenMitWhitespaceWerdenAlsExakteUtf8BytesAusgegeben() throws Exception {
        var r=request(); var first=put(r); success(first,r);
        // Altes gespeichertes Sieben-Feld-JSON samt Whitespace erhalten, nicht neu serialisieren.
        String legacy=" \n{\"requestId\":\""+r.get("requestId")+"\",\"zaehlblattId\":\""+SHEET+"\","
                +"\"entscheidung\":\"AKZEPTIERT\",\"blattStatus\":\"ABGESCHLOSSEN\",\"blattStatusVersion\":2,"
                +"\"ungezaehltePositionen\":2,\"abgeschlossenAm\":\"2026-10-15T08:00:00Z\"}\n ";
        jdbc.update("UPDATE sync_request SET antwort_json=? WHERE request_id=?::uuid",legacy,r.get("requestId").toString());
        var replay=put(r); assertEquals(200,replay.statusCode());
        assertArrayEquals(legacy.getBytes(StandardCharsets.UTF_8),replay.body());
        assertEquals(7,mapper.readTree(replay.body()).size());
    }
    @Test void medientypUndAndereSchreibmethodenBleibenAbgewiesen() throws Exception {
        error(send(PATH,mapper.writeValueAsString(request()),keys.token(),"PUT","text/plain"),415,"UNTERSTUETZTER_MEDIENTYP_ERFORDERLICH",null);
        error(send(PATH,mapper.writeValueAsString(request()),keys.token(),"POST","application/json"),403,"ZUGRIFF_VERWEIGERT",null);
        assertEquals(0,requests());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void fehlerNachStatusaenderungUndCommitGuardBestaetigenKeinenTeilabschluss(boolean guard) throws Exception {
        einzelnesBlatt(); var r=request(); String before=statusSnapshot();
        if(guard) doNothing().when(schreiber).akzeptiere(any(),anyString());
        else doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("Demo-interne-Fehlerdetails"); })
                .when(schreiber).akzeptiere(any(),anyString());
        var failed=put(r);
        error(failed,503,"SPEICHERUNG_VORUEBERGEHEND_NICHT_MOEGLICH","Speicherung derzeit nicht moeglich. Identischen Request erneut versuchen.");
        assertEquals("1",failed.headers().firstValue("Retry-After").orElseThrow());
        assertFalse(text(failed).contains("Demo-interne-Fehlerdetails"));
        assertEquals(before,statusSnapshot()); assertEquals(0,requests());
        reset(schreiber); success(put(r),r);
    }
    @Test void paralleleIdentischeHttpAnfragenBestaetigenGenauEinenLetztenAbschluss() throws Exception {
        einzelnesBlatt(); var r=request();
        var reached=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(call -> { reached.countDown(); if(!release.await(20,TimeUnit.SECONDS)) throw new IllegalStateException("Test-Timeout"); return call.callRealMethod(); })
                .when(schreiber).akzeptiere(any(),anyString());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(() -> put(r));
            try {
                assertTrue(reached.await(15,TimeUnit.SECONDS)); assertFalse(first.isDone());
                var second=pool.submit(() -> put(r));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8); boolean locked=false;
                while(System.nanoTime()<deadline) {
                    if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Integer.class)>0){locked=true;break;}
                    Thread.sleep(20);
                }
                assertTrue(locked); assertFalse(second.isDone()); release.countDown();
                var a=first.get(15,TimeUnit.SECONDS); var b=second.get(15,TimeUnit.SECONDS);
                success(a,r); assertEquals(200,b.statusCode()); assertArrayEquals(a.body(),b.body());
                assertEquals(1,requests());
                assertEquals(2,jdbc.queryForObject("SELECT status_version FROM zaehlblatt WHERE id=?::uuid",Long.class,SHEET));
                assertEquals(2,jdbc.queryForObject("SELECT status_version FROM zaehlsitzung WHERE id=?::uuid",Long.class,SESSION));
            } finally { release.countDown(); }
        }
    }

    private LinkedHashMap<String,Object> request() {
        var r=new LinkedHashMap<String,Object>(); r.put("requestId",UUID.randomUUID()); r.put("zaehlerlisteId",LIST);
        r.put("zuweisungVersion",1L); r.put("erwarteteSitzungStatusVersion",1L); r.put("erwarteteBlattStatusVersion",1L);
        r.put("abschlussBestaetigt",true); r.put("unvollstaendigBestaetigt",true); return r;
    }
    private HttpResponse<byte[]> put(Map<String,Object> r) throws Exception { return send(PATH,mapper.writeValueAsString(r),keys.token()); }
    private HttpResponse<byte[]> send(String path,String body,String token) throws Exception { return send(path,body,token,"PUT","application/json"); }
    private HttpResponse<byte[]> send(String path,String body,String token,String method,String contentType) throws Exception {
        try(var client=HttpClient.newHttpClient()) {
            var r=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type",contentType).header("X-Benutzer-Id",USER)
                    .method(method,HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8));
            if(token!=null) r.header("Authorization","Bearer "+token);
            return client.send(r.build(),HttpResponse.BodyHandlers.ofByteArray());
        }
    }
    private String token(String subject) { return keys.token(Map.of("sub",subject)); }
    private static String path(String session,String sheet) { return "/api/v1/sitzungen/"+session+"/blaetter/"+sheet+"/abschluss"; }
    private String text(HttpResponse<byte[]> r) { return new String(r.body(),StandardCharsets.UTF_8); }
    private JsonNode success(HttpResponse<byte[]> response,Map<String,Object> r) {
        assertEquals(200,response.statusCode(),text(response));
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        var json=mapper.readTree(response.body()); assertEquals(10,json.size());
        assertEquals(r.get("requestId").toString(),json.get("requestId").asString());
        assertEquals(SHEET,json.get("zaehlblattId").asString()); assertEquals("AKZEPTIERT",json.get("entscheidung").asString());
        assertEquals("ABGESCHLOSSEN",json.get("blattStatus").asString()); assertEquals(2,json.get("blattStatusVersion").asLong());
        assertNotNull(json.get("abgeschlossenAm")); assertArrayEquals(stored(r),response.body()); return json;
    }
    private void error(HttpResponse<byte[]> r,int status,String code,String detail) {
        assertEquals(status,r.statusCode(),text(r));
        assertTrue(r.headers().firstValue("Content-Type").orElseThrow().startsWith("application/json"));
        var json=mapper.readTree(r.body()); assertEquals(3,json.size());
        assertEquals(status,json.get("status").asInt()); assertEquals(code,json.get("code").asString());
        assertFalse(json.get("detail").asString().isBlank()); if(detail!=null) assertEquals(detail,json.get("detail").asString());
        assertFalse(text(r).contains("org.springframework")); assertFalse(text(r).contains("SELECT "));
        assertFalse(text(r).contains(SHEET)); assertFalse(text(r).contains(USER));
    }
    private byte[] stored(Map<String,Object> r) {
        return jdbc.queryForObject("SELECT antwort_json FROM sync_request WHERE request_id=?::uuid",String.class,r.get("requestId").toString()).getBytes(StandardCharsets.UTF_8);
    }
    private int requests() { return jdbc.queryForObject("SELECT count(*) FROM sync_request WHERE operation='BLATT_ABSCHLIESSEN'",Integer.class); }
    private void einzelnesBlatt() { jdbc.update("DELETE FROM zaehlblatt WHERE id=?::uuid",SECOND); }
    private String statusSnapshot() {
        return jdbc.queryForObject("""
                SELECT json_build_object('blaetter',(SELECT json_agg(b ORDER BY id) FROM zaehlblatt b),
                    'sitzungen',(SELECT json_agg(s ORDER BY id) FROM zaehlsitzung s))::text
                """,String.class);
    }
    private String datenSnapshot() {
        return jdbc.queryForObject("""
                SELECT json_build_object('ergebnisse',(SELECT json_agg(e ORDER BY id) FROM zaehlergebnis e),
                    'historie',(SELECT json_agg(h ORDER BY id) FROM ergebnis_historie h))::text
                """,String.class);
    }
    private void zaehleErstePosition(Integer menge) throws Exception {
        var r=new LinkedHashMap<String,Object>(); r.put("requestId",UUID.randomUUID()); r.put("zaehlerlisteId",LIST);
        r.put("zaehlblattId",SHEET); r.put("zuweisungVersion",1L); r.put("basisRevision",0L); r.put("lokaleRevision",1L);
        r.put("erwarteteBlattStatusVersion",1L); r.put("erwarteteSitzungStatusVersion",1L);
        r.put("zaehlmenge",menge); r.put("anmerkung","Demo: vor Abschluss");
        assertEquals(200,send("/api/v1/sitzungen/"+SESSION+"/positionen/"+POSITION+"/ergebnis",mapper.writeValueAsString(r),keys.token()).statusCode());
    }
}
