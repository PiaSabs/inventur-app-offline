package com.example.myapplication;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class ListenuebersichtTest {
    private Zaehlerliste lokal(int id, String name, boolean abgeschlossen) {
        return new Zaehlerliste(id, name, "" + id, "Lokal", "", true, abgeschlossen);
    }

    private ZaehlerlisteDto server(int id, String name) {
        return new ZaehlerlisteDto(id, name, "" + id, "Server", "Neu", true, false);
    }

    @Test public void ersterServerstandKannOhneLokalenImportAngezeigtWerden() {
        Listenuebersicht stand = Listenuebersicht.vomServer(Collections.emptyList(),
                Collections.singletonList(server(1, "Neue Liste")));
        assertTrue(stand.istServerstand());
        assertEquals("Neue Liste", stand.getListen().get(0).getBezeichnung());
        assertFalse(stand.istLokalVerfuegbar(1));
        assertFalse(stand.istNurLokal(1));
    }

    @Test public void gleicheIdentitaetZeigtNeueBezeichnungUndLokalenAbschluss() {
        Zaehlerliste alt = lokal(1, "Alt", true);
        Listenuebersicht stand = Listenuebersicht.vomServer(Collections.singletonList(alt),
                Collections.singletonList(server(1, "Neu")));
        assertEquals(1, stand.getListen().size());
        assertEquals("Neu", stand.getListen().get(0).getBezeichnung());
        assertEquals("Server", stand.getListen().get(0).getBereich());
        assertTrue(stand.getListen().get(0).istAbgeschlossen());
        assertEquals("Alt", alt.getBezeichnung());
        assertTrue(alt.istAbgeschlossen());
    }

    @Test public void lokalFehlendeServerlisteWirdErgaenztUndLokaleListeBleibt() {
        Listenuebersicht stand = Listenuebersicht.vomServer(Collections.singletonList(lokal(1, "Lokal", true)),
                Collections.singletonList(server(2, "Neu")));
        assertEquals(2, stand.getListen().size());
        assertTrue(stand.istNurLokal(1));
        assertTrue(stand.istLokalVerfuegbar(1));
        assertFalse(stand.istLokalVerfuegbar(2));
    }

    @Test public void leereServerantwortIstEinVollstaendigerStandMitErhaltenenLokalenListen() {
        Listenuebersicht stand = Listenuebersicht.vomServer(Collections.singletonList(lokal(1, "Alt", false)),
                Collections.emptyList());
        assertTrue(stand.istServerstand());
        assertEquals(1, stand.getListen().size());
        assertTrue(stand.istNurLokal(1));
    }

    @Test public void offlineStandIstAusdruecklichLokal() {
        Listenuebersicht stand = Listenuebersicht.lokal(Collections.singletonList(lokal(1, "Offline", true)));
        assertFalse(stand.istServerstand());
        assertTrue(stand.istNurLokal(1));
        assertTrue(stand.getListen().get(0).istAbgeschlossen());
    }

    @Test public void auswahlFolgtBlattIdBeiUmbenennungUndErhaeltLokaleRestblaetter() {
        Listenuebersicht stand = Listenuebersicht.vomServer(
                Arrays.asList(lokal(1, "Alt", true), lokal(3, "Alt", false)),
                Arrays.asList(server(1, "Neu"), server(2, "Neu"), server(4, "Alt")));
        List<Zaehlerliste> auswahl = stand.filtereAuswahl(1, "Alt");
        assertEquals(3, auswahl.size());
        assertEquals(1, auswahl.get(0).getId());
        assertEquals(2, auswahl.get(1).getId());
        assertEquals(3, auswahl.get(2).getId());
    }

    @Test public void doppelteOderFehlendeServeridentitaetWirdNichtAlsVollstaendigAkzeptiert() {
        for (List<ZaehlerlisteDto> fehlerhaft : Arrays.asList(
                Arrays.asList(server(1, "A"), server(1, "B")),
                Collections.singletonList(server(0, "Fehlt")),
                Arrays.asList(server(1, "A"), null))) {
            try {
                Listenuebersicht.vomServer(Collections.emptyList(), fehlerhaft);
                fail("Keine mehrdeutigen oder unvollstaendigen Serverlisten anzeigen");
            } catch (IllegalArgumentException erwartet) { }
        }
    }

    @Test public void nullAntwortIstKeinLeererErfolgreicherStand() {
        try { Listenuebersicht.vomServer(Collections.emptyList(), null); fail("Antwort fehlt"); }
        catch (IllegalArgumentException erwartet) { }
    }

    @Test public void anzeigestandKannNichtNachtraeglichVeraendertWerden() {
        Listenuebersicht stand = Listenuebersicht.vomServer(Collections.emptyList(),
                Collections.singletonList(server(1, "Liste")));
        try { stand.getListen().clear(); fail("Anzeigestand muss unveraenderlich sein"); }
        catch (UnsupportedOperationException erwartet) { }
    }

    @Test public void abrufzeitGehoertZumUnveraenderlichenAnzeigestand() {
        Listenuebersicht ohneZeit = Listenuebersicht.vomServer(Collections.emptyList(),
                Collections.singletonList(server(1, "Liste")));
        Listenuebersicht mitZeit = ohneZeit.mitAbrufzeit(12345L);
        assertNull(ohneZeit.getLetzterErfolgreicherAbrufMillis());
        assertEquals(Long.valueOf(12345L), mitZeit.getLetzterErfolgreicherAbrufMillis());
        assertTrue(mitZeit.istServerstand());
        assertEquals("Liste", mitZeit.getListen().get(0).getBezeichnung());
    }

    @Test public void offlineStandErhaeltDenLetztenErfolgOhneAktuellenServerstandZuBehaupten() {
        Listenuebersicht stand = Listenuebersicht.lokal(Collections.singletonList(lokal(1, "Lokal", true)), 12345L);
        assertFalse(stand.istServerstand());
        assertEquals(Long.valueOf(12345L), stand.getLetzterErfolgreicherAbrufMillis());
        assertTrue(stand.getListen().get(0).istAbgeschlossen());
    }
}
