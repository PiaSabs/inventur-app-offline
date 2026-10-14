package de.pia.inventur.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import de.pia.inventur.backend.entity.BenutzerEntity;
import de.pia.inventur.backend.entity.ZaehlpositionEntity;
import de.pia.inventur.backend.entity.ZaehlergebnisEntity;
import de.pia.inventur.backend.repository.ZaehlergebnisseRepository;
import de.pia.inventur.backend.service.ZaehlblattDetailsService;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import de.pia.inventur.backend.security.InventurPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@Sql(scripts = {"/test-demo.sql", "/test-blatt-sitzungen.sql", "/test-positionen.sql", "/test-ergebnisse.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class ZaehlergebnisIntegrationTests {

    @BeforeEach
    void interneTestidentitaet() {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new InventurPrincipal(UUID.fromString("50000000-0000-4000-8000-000000000001")), null, List.of()));
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void entferneTestidentitaet() { SecurityContextHolder.clearContext(); }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("inventur_test").withUsername("inventur_test")
            .withPassword(UUID.randomUUID().toString());

    private static final UUID SITZUNG_A = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final UUID SITZUNG_B = UUID.fromString("70000000-0000-4000-8000-000000000002");
    private static final UUID BLATT_A = UUID.fromString("82000000-0000-4000-8000-000000000001");
    private static final UUID BLATT_B = UUID.fromString("82000000-0000-4000-8000-000000000002");
    private static final UUID POSITION_OHNE = UUID.fromString("84000000-0000-4000-8000-000000000001");
    private static final UUID POSITION_NULLMENGE = UUID.fromString("84000000-0000-4000-8000-000000000002");
    private static final UUID BENUTZER_B = UUID.fromString("50000000-0000-4000-8000-000000000002");
    private static final UUID ERGEBNIS_A = UUID.fromString("85000000-0000-4000-8000-000000000001");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ZaehlergebnisseRepository ergebnisse;
    @Autowired
    private ZaehlblattDetailsService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;
    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void flywayV6AndHibernateValidateUseIsolatedDatabase() {
        assertEquals("inventur_test", jdbc.queryForObject("SELECT current_database()", String.class));
        assertEquals(9, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version='6' AND success", Integer.class));
        assertTrue(entityManager.getEntityManagerFactory().isOpen());
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns WHERE table_schema='public'
                AND table_name='zaehlergebnis' AND column_name IN ('sitzung_id', 'zaehlsitzung_id')
                """, Integer.class));
        assertEquals("timestamp with time zone", jdbc.queryForObject("""
                SELECT data_type FROM information_schema.columns WHERE table_schema='public'
                AND table_name='zaehlergebnis' AND column_name='geaendert_am'
                """, String.class));
    }

    @Test
    @Transactional
    void positiveResultCanBeStoredAndLoadedWithUserTimestampAndBigintRevision() {
        UUID id = UUID.randomUUID();
        Instant time = Instant.parse("2026-10-13T11:00:00Z");
        ergebnisse.saveAndFlush(new ZaehlergebnisEntity(id,
                entityManager.find(ZaehlpositionEntity.class, POSITION_OHNE), 17, "Demo-Kontrolle",
                3_000_000_000L, entityManager.find(BenutzerEntity.class, BENUTZER_B), time));
        entityManager.clear();
        var loaded = ergebnisse.findByZaehlposition_Id(POSITION_OHNE).orElseThrow();
        assertEquals(id, loaded.getId());
        assertEquals(17, loaded.getZaehlmenge());
        assertEquals("Demo-Kontrolle", loaded.getAnmerkung());
        assertEquals(3_000_000_000L, loaded.getRevision());
        assertEquals(time, loaded.getGeaendertAm());
        assertEquals("Demo-Zaehler B", loaded.getGeaendertVon().getAnzeigename());
        assertEquals(POSITION_OHNE, loaded.getZaehlposition().getId());
    }

    @Test
    @Transactional
    void nullQuantityWithNoteAndRevisionZeroCanBeStoredAndRead() throws Exception {
        ergebnisse.saveAndFlush(new ZaehlergebnisEntity(UUID.randomUUID(),
                entityManager.find(ZaehlpositionEntity.class, POSITION_OHNE), null,
                "Demo: Position noch pruefen", 0, null, null));
        entityManager.clear();
        var loaded = ergebnisse.findByZaehlposition_Id(POSITION_OHNE).orElseThrow();
        assertNull(loaded.getZaehlmenge());
        assertEquals("Demo: Position noch pruefen", loaded.getAnmerkung());
        assertEquals(0, loaded.getRevision());
        assertNull(loaded.getGeaendertVon());
        assertNull(loaded.getGeaendertAm());
        // Der Service muss auch eine gespeicherte NULL-Menge mit Anmerkung erhalten.
        var dto = service.findeBlatt(SITZUNG_A, BLATT_A).positionen().getLast().ergebnis();
        assertNull(dto.zaehlmenge());
        assertEquals("Demo: Position noch pruefen", dto.anmerkung());
        assertEquals(0, dto.revision());
    }

    @Test
    void zeroQuantityIsReturnedAsCountedZeroWithStoredRevision() throws Exception {
        var response = get(SITZUNG_A, BLATT_A);
        assertEquals(200, response.statusCode());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(""));
        var json = JsonMapper.builder().build().readTree(response.body());
        assertEquals(1, json.get("positionen").get(0).get("positionsnummer").asInt());
        var expected = JsonMapper.builder().build().readTree("""
                {"zaehlmenge":0,"anmerkung":"Demo: leer gezaehlt","revision":3}
                """);
        assertEquals(expected, json.get("positionen").get(0).get("ergebnis"));
        assertEquals(0, ergebnisse.findByZaehlposition_Id(POSITION_NULLMENGE).orElseThrow().getZaehlmenge());
    }

    @Test
    void missingResultIsLogicalInitialStateWithoutDatabaseWrite() throws Exception {
        long before = ergebnisse.count();
        assertTrue(ergebnisse.findByZaehlposition_Id(POSITION_OHNE).isEmpty());
        var response = get(SITZUNG_A, BLATT_A);
        assertEquals(200, response.statusCode());
        var mapper = JsonMapper.builder().build();
        var json = mapper.readTree(response.body());
        assertEquals(10, json.get("positionen").get(1).get("positionsnummer").asInt());
        assertEquals(mapper.readTree("""
                {"zaehlmenge":null,"anmerkung":null,"revision":0}
                """), json.get("positionen").get(1).get("ergebnis"));
        assertEquals(before, ergebnisse.count());
        assertTrue(ergebnisse.findByZaehlposition_Id(POSITION_OHNE).isEmpty());
    }

    @Test
    void bulkResultLoadingIsScopedToRequestedSheet() throws Exception {
        assertEquals(1, ergebnisse.findAllByZaehlposition_Zaehlblatt_Id(BLATT_A).size());
        assertEquals(1, ergebnisse.findAllByZaehlposition_Zaehlblatt_Id(BLATT_B).size());
        var json = JsonMapper.builder().build().readTree(get(SITZUNG_B, BLATT_B).body());
        assertEquals(12, json.get("positionen").get(0).get("ergebnis").get("zaehlmenge").asInt());
        assertEquals(2, json.get("positionen").get(0).get("ergebnis").get("revision").asLong());
    }

    @Test
    void negativeQuantitiesAndInvalidRevisionsAreRejected() throws Exception {
        for (String assignment : List.of("zaehlmenge=-1", "revision=-1")) {
            assertRejectedSql("UPDATE zaehlergebnis SET " + assignment + " WHERE id='" + ERGEBNIS_A + "'", "23514");
        }
        assertRejectedSql("UPDATE zaehlergebnis SET revision=NULL WHERE id='" + ERGEBNIS_A + "'", "23502");
    }

    @Test
    void atMostOneCurrentResultPerPositionIsAllowed() throws Exception {
        assertRejectedSql("INSERT INTO zaehlergebnis (id, zaehlposition_id, revision) VALUES "
                + "('86000000-0000-4000-8000-000000000001', '" + POSITION_NULLMENGE + "', 0)", "23505");
    }

    @Test
    void foreignKeysProtectPositionUserAndHistoricalData() throws Exception {
        assertRejectedSql("UPDATE zaehlergebnis SET zaehlposition_id='90000000-0000-4000-8000-000000000001' WHERE id='" + ERGEBNIS_A + "'", "23503");
        assertRejectedSql("UPDATE zaehlergebnis SET geaendert_von='90000000-0000-4000-8000-000000000001' WHERE id='" + ERGEBNIS_A + "'", "23503");
        assertRejectedSql("UPDATE zaehlergebnis SET zaehlposition_id=NULL WHERE id='" + ERGEBNIS_A + "'", "23502");
        assertRejectedSql("DELETE FROM zaehlposition WHERE id='" + POSITION_NULLMENGE + "'", "23503");
        assertRejectedSql("DELETE FROM benutzer WHERE id='" + BENUTZER_B + "'", "23503");
        assertEquals(2, ergebnisse.count());
    }

    @Test
    @Transactional
    void queryCountDoesNotGrowWithNumberOfPositionsOrResults() {
        var statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        entityManager.clear();
        statistics.clear();
        assertEquals(2, service.findeBlatt(SITZUNG_A, BLATT_A).positionen().size());
        long smallCount = statistics.getPrepareStatementCount();
        assertEquals(4, smallCount); // Aktuelle Rolle, berechtigtes Blatt, Positionen samt Artikeln, Ergebnisse.

        for (int number = 20; number < 30; number++) {
            UUID positionId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO zaehlposition (id, zaehlblatt_id, artikel_id, positionsnummer)
                    VALUES (?, ?, '83000000-0000-4000-8000-000000000001', ?)
                    """, positionId, BLATT_A, number);
            jdbc.update("INSERT INTO zaehlergebnis (id, zaehlposition_id, zaehlmenge, revision) VALUES (?, ?, ?, 1)",
                    UUID.randomUUID(), positionId, number);
        }
        entityManager.flush();
        entityManager.clear();
        statistics.clear();
        var detail = service.findeBlatt(SITZUNG_A, BLATT_A);
        assertEquals(12, detail.positionen().size());
        assertEquals(29, detail.positionen().getLast().ergebnis().zaehlmenge());
        assertEquals(smallCount, statistics.getPrepareStatementCount());
    }

    @Test
    @Transactional
    void emptySheetNeedsNoResultQueryAndCreatesNoInitialRows() {
        var statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        entityManager.clear();
        statistics.clear();
        var detail = service.findeBlatt(SITZUNG_A, UUID.fromString("82000000-0000-4000-8000-000000000003"));
        assertTrue(detail.positionen().isEmpty());
        assertEquals(3, statistics.getPrepareStatementCount());
    }

    private void assertRejectedSql(String sql, String state) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                var error = assertThrows(SQLException.class, () -> statement.executeUpdate(sql));
                assertEquals(state, error.getSQLState());
            } finally {
                connection.rollback();
            }
        }
    }

    private AuthentifizierteTestAnfragen.Antwort get(UUID sessionId, UUID sheetId) throws Exception {
        return AuthentifizierteTestAnfragen.lese(mockMvc, "/api/v1/sitzungen/" + sessionId + "/blaetter/" + sheetId);
    }
}
