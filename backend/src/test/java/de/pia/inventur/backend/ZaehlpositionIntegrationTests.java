package de.pia.inventur.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import de.pia.inventur.backend.entity.ArtikelEntity;
import de.pia.inventur.backend.entity.ZaehlblattEntity;
import de.pia.inventur.backend.entity.ZaehlpositionEntity;
import de.pia.inventur.backend.repository.ArtikelRepository;
import de.pia.inventur.backend.repository.ZaehlpositionenRepository;
import org.junit.jupiter.api.Test;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Sql(scripts = {"/test-demo.sql", "/test-blatt-sitzungen.sql", "/test-positionen.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class ZaehlpositionIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("inventur_test").withUsername("inventur_test")
            .withPassword(UUID.randomUUID().toString());

    private static final UUID SITZUNG_A = UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final UUID SITZUNG_B = UUID.fromString("70000000-0000-4000-8000-000000000002");
    private static final UUID BLATT_A = UUID.fromString("82000000-0000-4000-8000-000000000001");
    private static final UUID BLATT_B = UUID.fromString("82000000-0000-4000-8000-000000000002");
    private static final UUID ARTIKEL_A = UUID.fromString("83000000-0000-4000-8000-000000000001");
    private static final UUID POSITION_A = UUID.fromString("84000000-0000-4000-8000-000000000001");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ArtikelRepository artikel;
    @Autowired
    private ZaehlpositionenRepository positionen;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;
    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void flywayV5AndHibernateValidationUseOnlyIsolatedPostgres() {
        assertEquals("inventur_test", jdbc.queryForObject("SELECT current_database()", String.class));
        assertEquals(9, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version='5' AND success", Integer.class));
        assertTrue(entityManager.getEntityManagerFactory().isOpen());
        assertEquals("YES", jdbc.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema='public' AND table_name='zaehlposition' AND column_name='ist_menge'
                """, String.class));
    }

    @Test
    @Transactional
    void articleCanBePersistedAndLoaded() {
        UUID id = UUID.randomUUID();
        artikel.saveAndFlush(new ArtikelEntity(id, "DEMO-ART-03", "Demo-Filzmond"));
        entityManager.clear();
        var loaded = artikel.findById(id).orElseThrow();
        assertEquals(id, loaded.getId());
        assertEquals("DEMO-ART-03", loaded.getArtikelnummer());
        assertEquals("Demo-Filzmond", loaded.getBezeichnung());
    }

    @Test
    @Transactional
    void articleNumberIsNotAssumedToBeGloballyUnique() {
        UUID id = UUID.randomUUID();
        artikel.saveAndFlush(new ArtikelEntity(id, "DEMO-ART-01", "Demo-Variante"));
        entityManager.clear();
        assertEquals("DEMO-ART-01", artikel.findById(id).orElseThrow().getArtikelnummer());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM artikel WHERE artikelnummer='DEMO-ART-01'", Integer.class));
    }

    @Test
    void articleRequiresNonBlankNumberAndDescription() throws Exception {
        assertRejectedSql("UPDATE artikel SET artikelnummer='' WHERE id='" + ARTIKEL_A + "'", "23514");
        assertRejectedSql("UPDATE artikel SET artikelnummer='  ' WHERE id='" + ARTIKEL_A + "'", "23514");
        assertRejectedSql("UPDATE artikel SET bezeichnung='  ' WHERE id='" + ARTIKEL_A + "'", "23514");
        assertRejectedSql("UPDATE artikel SET artikelnummer=NULL WHERE id='" + ARTIKEL_A + "'", "23502");
        assertRejectedSql("UPDATE artikel SET bezeichnung=NULL WHERE id='" + ARTIKEL_A + "'", "23502");
    }

    @Test
    @Transactional
    void positionCanBeStoredAndLoadedWithLazyRelationshipsAndNullableValues() {
        UUID id = UUID.randomUUID();
        positionen.saveAndFlush(new ZaehlpositionEntity(id,
                entityManager.find(ZaehlblattEntity.class, BLATT_A), artikel.findById(ARTIKEL_A).orElseThrow(),
                2, null, null, null));
        entityManager.clear();
        var loaded = positionen.findById(id).orElseThrow();
        var util = entityManager.getEntityManagerFactory().getPersistenceUnitUtil();
        assertFalse(util.isLoaded(loaded, "zaehlblatt"));
        assertFalse(util.isLoaded(loaded, "artikel"));
        assertEquals(id, loaded.getId());
        assertEquals(BLATT_A, loaded.getZaehlblatt().getId());
        assertEquals(SITZUNG_A, loaded.getZaehlblatt().getZaehlsitzung().getId());
        assertEquals("Demo-Holzwuerfel", loaded.getArtikel().getBezeichnung());
        assertEquals(2, loaded.getPositionsnummer());
        assertNull(loaded.getLagerort());
        assertNull(loaded.getIstMenge());
        assertNull(loaded.getMindestbestand());
    }

    @Test
    @Transactional
    void multiplePositionsAreOrderedNumericallyAndSameArticleCanRepeatInOneSheet() {
        positionen.saveAndFlush(new ZaehlpositionEntity(UUID.randomUUID(),
                entityManager.find(ZaehlblattEntity.class, BLATT_A), artikel.findById(ARTIKEL_A).orElseThrow(),
                2, "Demo-Regal C", 0, 0));
        entityManager.clear();
        var loaded = positionen.findAllByZaehlblatt_IdOrderByPositionsnummerAsc(BLATT_A);
        assertEquals(List.of(1, 2, 10), loaded.stream().map(ZaehlpositionEntity::getPositionsnummer).toList());
        assertEquals(loaded.get(1).getArtikel().getId(), loaded.get(2).getArtikel().getId());
    }

    @Test
    @Transactional
    void sameArticleHasDistinctPositionIdsInDifferentRounds() {
        var first = positionen.findAllByZaehlblatt_IdOrderByPositionsnummerAsc(BLATT_A).getLast();
        var second = positionen.findAllByZaehlblatt_IdOrderByPositionsnummerAsc(BLATT_B).getFirst();
        assertEquals(first.getArtikel().getId(), second.getArtikel().getId());
        assertNotEquals(first.getId(), second.getId());
        assertEquals(11, first.getZaehlblatt().getZaehlsitzung().getRundeNr());
        assertEquals(12, second.getZaehlblatt().getZaehlsitzung().getRundeNr());
        assertNotEquals(first.getZaehlblatt().getZaehlsitzung().getId(), second.getZaehlblatt().getZaehlsitzung().getId());
        assertEquals(3, positionen.count());
    }

    @Test
    void duplicatePositionNumberInSameSheetIsRejected() throws Exception {
        assertRejectedSql("UPDATE zaehlposition SET positionsnummer=1 WHERE id='" + POSITION_A + "'", "23505");
    }

    @Test
    void foreignKeysAndHistoryAreProtectedWithoutDeleteCascades() throws Exception {
        assertRejectedSql("UPDATE zaehlposition SET zaehlblatt_id='90000000-0000-4000-8000-000000000001' WHERE id='" + POSITION_A + "'", "23503");
        assertRejectedSql("UPDATE zaehlposition SET artikel_id='90000000-0000-4000-8000-000000000001' WHERE id='" + POSITION_A + "'", "23503");
        assertRejectedSql("UPDATE zaehlposition SET zaehlblatt_id=NULL WHERE id='" + POSITION_A + "'", "23502");
        assertRejectedSql("UPDATE zaehlposition SET artikel_id=NULL WHERE id='" + POSITION_A + "'", "23502");
        assertRejectedSql("DELETE FROM zaehlblatt WHERE id='" + BLATT_A + "'", "23503");
        assertRejectedSql("DELETE FROM artikel WHERE id='" + ARTIKEL_A + "'", "23503");
        assertEquals(3, positionen.count());
    }

    @Test
    void invalidPositionNumbersAndNegativeStockValuesAreRejected() throws Exception {
        for (String assignment : List.of("positionsnummer=0", "positionsnummer=-1", "ist_menge=-1", "mindestbestand=-1")) {
            assertRejectedSql("UPDATE zaehlposition SET " + assignment + " WHERE id='" + POSITION_A + "'", "23514");
        }
        assertRejectedSql("UPDATE zaehlposition SET positionsnummer=NULL WHERE id='" + POSITION_A + "'", "23502");
    }

    @Test
    void detailEndpointReturnsExactSheetPositionsAndArticleDtos() throws Exception {
        var response = get(SITZUNG_A.toString(), BLATT_A.toString());
        assertEquals(200, response.statusCode());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElse(""));
        var mapper = JsonMapper.builder().build();
        assertEquals(mapper.readTree("""
                {"id":"82000000-0000-4000-8000-000000000001",
                 "sitzungId":"70000000-0000-4000-8000-000000000001",
                 "blattnummer":"1","status":"OFFEN","statusVersion":1,"abgeschlossenAm":null,
                 "positionen":[
                   {"id":"84000000-0000-4000-8000-000000000002","positionsnummer":1,
                    "lagerort":"Demo-Regal A","istMenge":0,"mindestbestand":5,
                    "artikel":{"id":"83000000-0000-4000-8000-000000000002",
                               "artikelnummer":"DEMO-ART-02","bezeichnung":"Demo-Papierstern"},
                    "ergebnis":{"zaehlmenge":null,"anmerkung":null,"revision":0}},
                   {"id":"84000000-0000-4000-8000-000000000001","positionsnummer":10,
                    "lagerort":null,"istMenge":null,"mindestbestand":null,
                    "artikel":{"id":"83000000-0000-4000-8000-000000000001",
                               "artikelnummer":"DEMO-ART-01","bezeichnung":"Demo-Holzwuerfel"},
                    "ergebnis":{"zaehlmenge":null,"anmerkung":null,"revision":0}}
                 ]}
                """), mapper.readTree(response.body()));
    }

    @Test
    void detailEndpointReturnsEmptyPositionsForExistingSheet() throws Exception {
        var response = get(SITZUNG_A.toString(), "82000000-0000-4000-8000-000000000003");
        assertEquals(200, response.statusCode());
        var json = JsonMapper.builder().build().readTree(response.body());
        assertEquals(0, json.get("positionen").size());
    }

    @Test
    void detailEndpointReturns404ForUnknownSheetSessionOrWrongAssignment() throws Exception {
        assertEquals(404, get(SITZUNG_A.toString(), "90000000-0000-4000-8000-000000000001").statusCode());
        assertEquals(404, get(SITZUNG_B.toString(), BLATT_A.toString()).statusCode());
        assertEquals(404, get("90000000-0000-4000-8000-000000000001", BLATT_A.toString()).statusCode());
    }

    @Test
    void detailEndpointReturns400ForEitherMalformedUuid() throws Exception {
        assertEquals(400, get("keine-uuid", BLATT_A.toString()).statusCode());
        assertEquals(400, get(SITZUNG_A.toString(), "keine-uuid").statusCode());
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

    private AuthentifizierteTestAnfragen.Antwort get(String sessionId, String sheetId) throws Exception {
        return AuthentifizierteTestAnfragen.lese(mockMvc, "/api/v1/sitzungen/" + sessionId + "/blaetter/" + sheetId);
    }
}
