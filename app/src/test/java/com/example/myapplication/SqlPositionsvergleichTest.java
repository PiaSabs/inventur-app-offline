package com.example.myapplication;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class SqlPositionsvergleichTest {
    private SqlPosition sql(long id, int pos, String artikel) {
        return new SqlPosition(id, 41, pos, artikel);
    }
    private Artikel lokal(long id, int pos, String artikel) {
        return new Artikel(pos, artikel, "Test", "L1", 3, null, 1, null, "", id, 41);
    }
    private SqlPositionsvergleich pruefe(Artikel a, SqlPosition... server) {
        return SqlPositionsvergleich.vergleiche(41, Collections.singletonList(a), Arrays.asList(server));
    }

    @Test public void identischerBestand() {
        SqlPositionsvergleich v = pruefe(lokal(701,1,"TEST-A"), sql(701,1,"TEST-A"));
        assertEquals(1,v.getBekannt());
        assertEquals(0,v.getUnklar());
        assertTrue(v.getAnzeige().startsWith("Keine Positionsänderungen erkannt"));
        assertTrue(v.getAnzeige().contains("kein Zähldurchgangsabgleich"));
    }
    @Test public void neueIdentitaetBleibtPruefbeduerftig() {
        SqlPositionsvergleich v = pruefe(lokal(701,1,"TEST-A"), sql(701,1,"TEST-A"),sql(702,2,"TEST-B"));
        assertEquals(1,v.getUnbekannt());
        assertTrue(v.getAnzeige().startsWith("SQL-Positionsänderungen gefunden – Prüfung erforderlich"));
    }
    @Test public void fehlendePositionBeiVollstaendigemErgebnis() {
        assertEquals(1,pruefe(lokal(701,1,"TEST-A")).getFehlend());
    }
    @Test public void verschiebungAnhandBekannterId() {
        assertEquals(1,pruefe(lokal(701,1,"TEST-A"),sql(701,9,"TEST-A")).getGeaendert());
    }
    @Test public void artikelwechselAnhandBekannterId() {
        assertEquals(1,pruefe(lokal(701,1,"TEST-A"),sql(701,1,"TEST-B")).getGeaendert());
    }
    @Test public void doppelteServerIdsErlaubenKeineFehlendAussage() {
        SqlPositionsvergleich v = pruefe(lokal(701,1,"TEST-A"),sql(701,1,"TEST-A"),sql(701,2,"TEST-B"));
        assertEquals(0,v.getBekannt()); assertEquals(0,v.getFehlend()); assertTrue(v.getUnklar()>0);
    }
    @Test public void doppelteLokaleIdsErlaubenKeineNeuAussage() {
        SqlPositionsvergleich v = SqlPositionsvergleich.vergleiche(41,
                Arrays.asList(lokal(701,1,"TEST-A"),lokal(701,2,"TEST-B")),
                Collections.singletonList(sql(701,1,"TEST-A")));
        assertEquals(0,v.getUnbekannt()); assertTrue(v.getUnklar()>0);
    }
    @Test public void fehlendeIdIstUnklar() {
        SqlPositionsvergleich v = pruefe(lokal(701,1,"TEST-A"),new SqlPosition(null,41,1,"TEST-A"));
        assertEquals(0,v.getFehlend()); assertTrue(v.getAnzeige().contains("Zuordnung ungeklärt"));
    }
    @Test public void altbestandWirdNichtNachtraeglichZugeordnet() {
        SqlPositionsvergleich v = pruefe(lokal(0,1,"TEST-A"),sql(701,1,"TEST-A"));
        assertEquals(0,v.getBekannt()); assertEquals(0,v.getUnbekannt()); assertTrue(v.getUnklar()>0);
    }
    @Test public void falschesBlattIstUnklar() {
        SqlPositionsvergleich v = pruefe(lokal(701,1,"TEST-A"),new SqlPosition(701L,42,1,"TEST-A"));
        assertEquals(0,v.getGeaendert()); assertEquals(0,v.getFehlend()); assertTrue(v.getUnklar()>0);
    }
    @Test public void neueIdAnBelegterPositionIstKeineSichereNeuanlage() {
        SqlPositionsvergleich v = pruefe(lokal(701,1,"TEST-A"),sql(702,1,"TEST-B"));
        assertEquals(0,v.getUnbekannt()); assertEquals(0,v.getFehlend()); assertTrue(v.getUnklar()>0);
    }
    @Test public void neueIdMitBekanntemArtikelIstUnklar() {
        SqlPositionsvergleich v = pruefe(lokal(701,1,"TEST-A"),sql(702,2,"TEST-A"));
        assertEquals(0,v.getUnbekannt()); assertEquals(0,v.getFehlend()); assertTrue(v.getUnklar()>0);
    }
    @Test public void doppeltePositionsnummernSindUnklar() {
        SqlPositionsvergleich v = pruefe(lokal(701,1,"TEST-A"),sql(701,1,"TEST-A"),sql(702,1,"TEST-B"));
        assertEquals(0,v.getBekannt()); assertTrue(v.getUnklar()>0);
    }
    @Test public void unvollstaendigeAntwortIstKeinUnveraenderterBestand() {
        for (SqlPosition p : Arrays.asList(null,new SqlPosition(701L,41,null,"TEST-A"),sql(701,1,""))) {
            SqlPositionsvergleich v = pruefe(lokal(701,1,"TEST-A"),p);
            assertTrue(v.getUnklar()>0); assertEquals(0,v.getFehlend());
        }
        assertFalse(SqlPositionsvergleich.vergleiche(41,Collections.emptyList(),null).istAbrufErfolgreich());
    }
    @Test public void mengenNullUndNullmengeSowieNotizenBleibenUnveraendert() {
        Artikel a=lokal(701,1,"TEST-A"), b=lokal(702,2,"TEST-B");
        a.setAnmerkung("Offene Notiz"); b.setZaehlMenge(0);
        SqlPositionsvergleich.vergleiche(41,Arrays.asList(a,b),Collections.emptyList());
        assertNull(a.getZaehlMenge()); assertEquals(Integer.valueOf(0),b.getZaehlMenge());
        assertEquals("Offene Notiz",a.getAnmerkung());
    }
    @Test public void ausfallBehauptetKeineErfolgreichePruefung() {
        String text=SqlPositionsvergleich.nichtVerfuegbar().getAnzeige();
        assertFalse(text.contains("Keine Positionsänderungen erkannt"));
        assertTrue(text.contains("nicht verfügbar"));
    }
    @Test public void gleichePositionsnummerImAnderenBlattIstKeinIdentitaetsersatz() {
        SqlPositionsvergleich v=SqlPositionsvergleich.vergleiche(42,
                Collections.singletonList(new Artikel(1,"TEST-A","Test","L1",3,null,1,null,"",702L,42)),
                Collections.singletonList(new SqlPosition(702L,42,1,"TEST-A")));
        assertEquals(1,v.getBekannt()); assertEquals(0,v.getUnklar());
    }
}
