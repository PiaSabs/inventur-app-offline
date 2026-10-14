package de.pia.inventur.backend;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.stream.Stream;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import de.pia.inventur.backend.entity.BenutzerEntity;
import de.pia.inventur.backend.entity.BenutzerRolle;
import de.pia.inventur.backend.repository.BenutzerRepository;
import de.pia.inventur.backend.security.GepruefteOidcIdentitaet;
import de.pia.inventur.backend.security.OidcBenutzerZuordnung;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Sql(scripts = "/test-demo.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@Transactional
class OidcBenutzerZuordnungIntegrationTests {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("inventur_test").withUsername("inventur_test").withPassword(UUID.randomUUID().toString());
    private static final UUID USER_A = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID USER_B = UUID.fromString("50000000-0000-4000-8000-000000000002");
    private static final UUID ADMIN = UUID.fromString("50000000-0000-4000-8000-000000000003");
    private static final String ISSUER = "https://login.demo.example/realm/inventur";
    private static final String SUBJECT = "demo-subject-a";
    @Autowired private BenutzerRepository benutzer;
    @Autowired private OidcBenutzerZuordnung zuordnung;
    @Autowired private JdbcTemplate jdbc;
    @PersistenceContext private EntityManager em;

    @Test
    void flywayV8AndHibernateValidateOptionalTextFieldsInIsolatedDatabase() {
        assertEquals("inventur_test", jdbc.queryForObject("SELECT current_database()", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='8' AND success", Integer.class));
        assertEquals(2, jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='benutzer'
                AND column_name IN ('oidc_issuer', 'oidc_subject') AND data_type='text' AND is_nullable='YES'
                """, Integer.class));
        // Der Spring-Kontext startet mit spring.jpa.hibernate.ddl-auto=validate.
        assertEquals(3, benutzer.count());
        benutzer.findAll().forEach(user -> { assertNull(user.getOidcIssuer()); assertNull(user.getOidcSubject()); });
    }

    @Test
    void entityAndRepositoryRoundTripUsesExactIssuerSubjectPair() {
        UUID id = UUID.randomUUID();
        benutzer.saveAndFlush(new BenutzerEntity(id, "Demo-OIDC-Zaehler", BenutzerRolle.ZAEHLER, true, ISSUER, SUBJECT));
        em.clear();
        var user = benutzer.findByOidcIssuerAndOidcSubject(ISSUER, SUBJECT).orElseThrow();
        assertEquals(id, user.getId()); assertEquals(ISSUER, user.getOidcIssuer()); assertEquals(SUBJECT, user.getOidcSubject());
        assertEquals(BenutzerRolle.ZAEHLER, user.getRolle()); assertTrue(user.isAktiv());
    }

    @Test
    void duplicatePairIsRejectedByPostgres() {
        link(USER_A, ISSUER, SUBJECT);
        var error = assertThrows(DataIntegrityViolationException.class, () -> link(USER_B, ISSUER, SUBJECT));
        assertEquals("23505", sqlCause(error).getSQLState());
    }

    @Test
    void sameSubjectAtDifferentIssuersResolvesTwoDistinctUsers() {
        link(USER_A, ISSUER, SUBJECT); link(USER_B, "https://other.demo.example/realm/inventur", SUBJECT);
        assertEquals(USER_A, zuordnung.findeAktivenBenutzer(identity(ISSUER, SUBJECT)).orElseThrow());
        assertEquals(USER_B, zuordnung.findeAktivenBenutzer(identity("https://other.demo.example/realm/inventur", SUBJECT)).orElseThrow());
    }

    static Stream<Arguments> invalidPairs() {
        return Stream.of(Arguments.of(ISSUER, null), Arguments.of(null, SUBJECT),
                Arguments.of("", SUBJECT), Arguments.of(ISSUER, ""),
                Arguments.of("   ", SUBJECT), Arguments.of(ISSUER, "   "),
                Arguments.of("\t\r\n", SUBJECT), Arguments.of(ISSUER, "\t\r\n"),
                Arguments.of("\u00a0\u2003\u202f\u0085", SUBJECT), Arguments.of(ISSUER, "\u00a0\u2003\u202f\u0085"));
    }

    @ParameterizedTest @MethodSource("invalidPairs")
    void databaseRejectsHalfMissingEmptyAndWhitespaceOnlyPairs(String issuer, String subject) {
        var error = assertThrows(DataIntegrityViolationException.class, () -> link(USER_A, issuer, subject));
        assertEquals("23514", sqlCause(error).getSQLState());
    }

    @ParameterizedTest @MethodSource("invalidPairs")
    void internalIdentityCarrierRejectsInvalidFieldsWithoutDisclosingTheirValues(String issuer, String subject) {
        var error = assertThrows(IllegalArgumentException.class, () -> identity(issuer, subject));
        assertEquals("Issuer und Subject sind erforderlich", error.getMessage());
    }

    @Test
    void activeIdentityReturnsOnlyLocalUuidAndInactiveUnknownReturnSameEmptyResult() {
        link(USER_A, ISSUER, SUBJECT); link(USER_B, ISSUER, "demo-inaktiv");
        jdbc.update("UPDATE benutzer SET aktiv=false WHERE id=?", USER_B);
        assertEquals(USER_A, zuordnung.findeAktivenBenutzer(identity(ISSUER, SUBJECT)).orElseThrow());
        var inactive = zuordnung.findeAktivenBenutzer(identity(ISSUER, "demo-inaktiv"));
        var unknown = zuordnung.findeAktivenBenutzer(identity(ISSUER, "demo-unbekannt"));
        assertTrue(inactive.isEmpty()); assertEquals(inactive, unknown);
    }

    @Test
    void mappingDoesNotGrantRolesCreateUsersOrChangeAssignments() {
        link(USER_A, ISSUER, SUBJECT); link(ADMIN, ISSUER, "demo-admin");
        String usersBefore = jdbc.queryForObject("SELECT json_agg(b ORDER BY id)::text FROM benutzer b", String.class);
        String listsBefore = jdbc.queryForObject("SELECT json_agg(l ORDER BY id)::text FROM zaehlerliste l", String.class);
        assertEquals(USER_A, zuordnung.findeAktivenBenutzer(identity(ISSUER, SUBJECT)).orElseThrow());
        // Identitaetszuordnung erlaubt auch Admins, erteilt aber kein Zaehler-Schreibrecht.
        assertEquals(ADMIN, zuordnung.findeAktivenBenutzer(identity(ISSUER, "demo-admin")).orElseThrow());
        assertTrue(zuordnung.findeAktivenBenutzer(identity(ISSUER, "demo-unbekannt")).isEmpty());
        assertEquals(3, benutzer.count());
        assertEquals(usersBefore, jdbc.queryForObject("SELECT json_agg(b ORDER BY id)::text FROM benutzer b", String.class));
        assertEquals(listsBefore, jdbc.queryForObject("SELECT json_agg(l ORDER BY id)::text FROM zaehlerliste l", String.class));
    }

    @Test
    void caseAndWhitespaceAreNeverNormalizedIntoAnotherIdentity() {
        link(USER_A, ISSUER, SUBJECT);
        assertTrue(zuordnung.findeAktivenBenutzer(identity(ISSUER, "DEMO-SUBJECT-A")).isEmpty());
        assertTrue(zuordnung.findeAktivenBenutzer(identity(ISSUER + "/", SUBJECT)).isEmpty());
        assertTrue(zuordnung.findeAktivenBenutzer(identity(ISSUER, " " + SUBJECT + " ")).isEmpty());
    }

    @Test
    void migrationFromV7PreservesExistingUsersRolesAndAssignmentsWithoutLinkingThem() throws Exception {
        // Separates Schema innerhalb desselben wegwerfbaren Testcontainers, keine Entwicklungs-DB.
        String schema = "oidc_upgrade_test";
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema).defaultSchema(schema).target("7").load().migrate();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setSchema(schema);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO benutzer(id, anzeigename, rolle, aktiv) VALUES
                        ('50000000-0000-4000-8000-000000000001', 'Demo-Upgrade-Zaehler', 'ZAEHLER', true),
                        ('50000000-0000-4000-8000-000000000002', 'Demo-Upgrade-Admin', 'ADMIN', false)
                        """);
                statement.executeUpdate("""
                        INSERT INTO zaehlerliste(id, code, bezeichnung, aktueller_zaehler_id, zuweisung_version)
                        VALUES ('10000000-0000-4000-8000-000000000001', 'DEMO-UPGRADE', 'Demo-Upgradeliste',
                            '50000000-0000-4000-8000-000000000001', 4)
                        """);
            }
            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .schemas(schema).defaultSchema(schema).load().migrate();
            try (var statement = connection.createStatement(); var result = statement.executeQuery("""
                    SELECT count(*) FROM benutzer WHERE oidc_issuer IS NULL AND oidc_subject IS NULL
                        AND ((anzeigename='Demo-Upgrade-Zaehler' AND rolle='ZAEHLER' AND aktiv)
                        OR (anzeigename='Demo-Upgrade-Admin' AND rolle='ADMIN' AND NOT aktiv))
                    """)) { assertTrue(result.next()); assertEquals(2, result.getInt(1)); }
            try (var statement = connection.createStatement(); var result = statement.executeQuery("""
                    SELECT count(*) FROM zaehlerliste WHERE aktueller_zaehler_id='50000000-0000-4000-8000-000000000001'
                        AND zuweisung_version=4 AND code='DEMO-UPGRADE'
                    """)) { assertTrue(result.next()); assertEquals(1, result.getInt(1)); }
        }
    }

    private void link(UUID id, String issuer, String subject) {
        jdbc.update("UPDATE benutzer SET oidc_issuer=?, oidc_subject=? WHERE id=?", issuer, subject, id);
    }
    private GepruefteOidcIdentitaet identity(String issuer, String subject) { return new GepruefteOidcIdentitaet(issuer, subject); }
    private SQLException sqlCause(Throwable error) {
        while (error != null && !(error instanceof SQLException)) error = error.getCause();
        assertInstanceOf(SQLException.class, error); return (SQLException) error;
    }
}
