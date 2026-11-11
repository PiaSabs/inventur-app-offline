package com.example.myapplication;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SqlSchreibsperreRepositoryTest {
    private Context context;
    private String name;
    private LokalerInventurSpeicher speicher;
    private LesequelleFake quelle;
    private ArtikelRepository repository;

    @Before public void vorbereiten() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        name = "sql_schreibsperre_" + UUID.randomUUID() + ".db";
        speicher = new LokalerInventurSpeicher(context, name);
        quelle = new LesequelleFake();
        repository = new ArtikelRepository(quelle, speicher);
        speicher.initialisiereZaehlerlisten(quelle.ladeZaehlerlisten());
        speicher.initialisiereArtikel(99001, quelle.ladeArtikel(99001));
    }

    @After public void aufraeumen() { speicher.close(); context.deleteDatabase(name); }

    private void lokalAendern() {
        List<Artikel> artikel = speicher.ladeArtikel(99001);
        artikel.get(0).setZaehlMenge(0);
        artikel.get(0).setAnmerkung("Lokale Notiz erhalten");
        artikel.get(1).setZaehlMenge(null);
        assertTrue(repository.speichereZaehlmengenNurLokal(99001, "Testzaehler", "Testschreiber", artikel).istErfolgreich());
    }

    private void pruefeLokalenStand() {
        List<Artikel> artikel = speicher.ladeArtikel(99001);
        assertEquals(Integer.valueOf(0), artikel.get(0).getZaehlMenge());
        assertEquals("Lokale Notiz erhalten", artikel.get(0).getAnmerkung());
        assertNull(artikel.get(1).getZaehlMenge());
        assertTrue(speicher.hatOffeneSynchronisierungFuerZaehlerliste(99001));
        assertEquals(0, quelle.schreibaufrufe);
    }

    @Test public void automatischeSynchronisierungUndNetzwechselErhaltenOffeneAenderungen() {
        lokalAendern();
        repository.ladeZaehlerlisten();
        repository.pruefeDatenbankverbindungUndSynchronisiereBeiBedarf();
        repository.verarbeiteNetzwerkWiederVerfuegbar();
        pruefeLokalenStand();
        assertTrue(quelle.verbindungspruefungen > 0);
    }

    @Test public void manuelleSynchronisierungKannDieSperreNichtUmgehen() {
        lokalAendern();
        repository.pruefeDatenbankverbindungUndSynchronisiereSofort();
        ZaehlmengenSpeicherAntwort antwort = repository.speichereZaehlmengenMitSynchronisierung(
                99001, "Testzaehler", "Testschreiber", speicher.ladeArtikel(99001));
        assertFalse(antwort.istErfolgreich());
        assertNull(antwort.getReferenzId());
        assertTrue(antwort.getMeldung().contains("Lokal gespeichert"));
        pruefeLokalenStand();
    }

    @Test public void abschlussBleibtLokalUndUnbestaetigt() {
        lokalAendern();
        assertTrue(repository.markiereZaehlerlisteAlsAbgeschlossen(99001));
        repository.pruefeDatenbankverbindungUndSynchronisiereSofort();
        assertTrue(speicher.ladeZaehlerlisteFuerId(99001).istAbgeschlossen());
        pruefeLokalenStand();
    }

    @Test public void warteschlangeWirdAuchBeiFehlendemLokalemBlattNichtQuittiert() {
        speicher.merkeZaehlerlisteFuerSynchronisierung(99999);
        repository.pruefeDatenbankverbindungUndSynchronisiereSofort();
        assertTrue(speicher.hatOffeneSynchronisierungFuerZaehlerliste(99999));
        assertEquals(0, quelle.schreibaufrufe);
    }

    @Test public void lesenBleibtMitSchreibsperreMoeglich() {
        speicher.loescheAlleDaten();
        assertEquals(1, repository.ladeZaehlerlisten().size());
        assertEquals(2, repository.ladeArtikel(99001).size());
        assertTrue(repository.istSqlSchreibenGesperrt());
        assertTrue(quelle.leseaufrufe > 0);
        assertEquals(0, quelle.schreibaufrufe);
    }

    @Test public void jdbcSperreBlockiertVorDemTreiberAuchAufAndroid() throws Exception {
        int[] aufrufe = {0};
        Connection treiber = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, methode, argumente) -> {
                    if (!methode.getName().equals("prepareStatement")) return null;
                    aufrufe[0]++;
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                            (statement, aufruf, parameter) -> {
                                if (!aufruf.getName().equals("executeQuery")) return null;
                                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                                        (result, lesen, spalte) -> lesen.getName().equals("next") ? true : null);
                            });
                });
        Connection sicher = SqlServerSchreibschutz.sichere(treiber);
        try { sicher.prepareStatement("UPDATE artikel SET menge=0"); fail("Schreiben muss vor dem Treiber scheitern"); }
        catch (SQLException exception) { assertEquals("25006", exception.getSQLState()); }
        assertEquals(0, aufrufe[0]);
        try (PreparedStatement statement = sicher.prepareStatement("SELECT 1"); ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            assertEquals(1, aufrufe[0]);
            try { statement.executeUpdate(); fail("Kein direkter JDBC-Schreibaufruf erlaubt"); }
            catch (SQLException exception) { assertEquals("25006", exception.getSQLState()); }
        }
    }

    @Test public void offlineOeffnenUndNeustartBehaltenMengenNotizenUndAbschluss() {
        lokalAendern();
        repository.markiereZaehlerlisteAlsAbgeschlossen(99001);
        quelle.offline = true;
        speicher.close();
        speicher = new LokalerInventurSpeicher(context, name);
        repository = new ArtikelRepository(quelle, speicher);
        assertEquals(2, repository.ladeArtikel(99001).size());
        repository.pruefeDatenbankverbindungUndSynchronisiereSofort();
        assertTrue(speicher.ladeZaehlerlisteFuerId(99001).istAbgeschlossen());
        pruefeLokalenStand();
    }

    private static final class LesequelleFake implements InventurRemoteDataSource {
        int schreibaufrufe;
        int leseaufrufe;
        int verbindungspruefungen;
        boolean offline;
        @Override public boolean istSchreibgeschuetzt() { return !SqlServerSchreibschutz.istSchreibenErlaubt(); }
        @Override public void pruefeVerbindung() throws RemoteDatenException {
            verbindungspruefungen++;
            if (offline) throw new RemoteDatenException("Testverbindung offline");
        }
        @Override public List<ZaehlerlisteDto> ladeZaehlerlisten() {
            leseaufrufe++;
            return Collections.singletonList(new ZaehlerlisteDto(99001, "Testliste", "99001", "Test", "11.11.2026", true, false));
        }
        @Override public List<ArtikelDto> ladeArtikel(int id) {
            leseaufrufe++;
            return Arrays.asList(new ArtikelDto(1, "TEST-1", "Testartikel", "R1", 10, 1, 5),
                    new ArtikelDto(2, "TEST-2", "Testartikel", "R2", 10, 1, 0));
        }
        @Override public ZaehlmengenSpeicherAntwort speichereZaehlmengen(ZaehlmengenSpeicherAnfrage anfrage) {
            schreibaufrufe++;
            throw new AssertionError("Schreibaufruf darf die gesperrte Datenquelle nicht erreichen");
        }
        @Override public void markiereZaehlerlisteAlsAbgeschlossen(int id) {
            schreibaufrufe++;
            throw new AssertionError("Kein SQL-Abschluss erlaubt");
        }
        @Override public String holeLetzteDiagnose() { return ""; }
    }
}
