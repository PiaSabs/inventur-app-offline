package com.example.myapplication;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class SqlPositionsidentitaetTest {
    private ArtikelDto sql(int id, int blatt, Integer menge) {
        return ApiInventurRemoteDataSource.mappeSqlPosition(new LokalerInventurSpeicher.BestandszaehlungSeed(
                id, 3, "TEST-A", "L1", 8, menge, 0, 1, "Testartikel"), blatt);
    }

    @Test public void positionsIdUndBlattErreichenDasArtikelmodell() {
        ArtikelDto dto = sql(771, 41, null);
        Artikel artikel = ArtikelMapper.mappe(dto);
        assertEquals(Long.valueOf(771), dto.getSqlPositionsId());
        assertEquals(Long.valueOf(771), artikel.getSqlPositionsId());
        assertEquals(Integer.valueOf(41), artikel.getQuellBlattnummer());
        assertEquals(3, artikel.getPositionsNummer());
        assertNull(artikel.getZaehlMenge());
        assertEquals(Integer.valueOf(0), artikel.getLetzteZaehlmenge());
    }

    @Test public void doppelteArtikelnummernBehaltenIhreUnterschiedlichenIdentitaeten() {
        List<Artikel> artikel = ArtikelMapper.mappeListe(Arrays.asList(sql(771,41,null), sql(772,41,0)));
        assertEquals(2, artikel.size());
        assertEquals(artikel.get(0).getArtikelnummer(), artikel.get(1).getArtikelnummer());
        assertNotEquals(artikel.get(0).getSqlPositionsId(), artikel.get(1).getSqlPositionsId());
        assertNull(artikel.get(0).getZaehlMenge());
        assertEquals(Integer.valueOf(0), artikel.get(1).getZaehlMenge());
    }

    @Test public void gleichePositionsnummerInVerschiedenenBlaetternBleibtGetrennt() {
        Artikel a = ArtikelMapper.mappe(sql(771,41,null));
        Artikel b = ArtikelMapper.mappe(sql(772,42,null));
        assertEquals(a.getPositionsNummer(), b.getPositionsNummer());
        assertNotEquals(a.getQuellBlattnummer(), b.getQuellBlattnummer());
    }

    @Test public void fehlendeOderUngueltigeQuellidentitaetBleibtUngeklaert() {
        for (ArtikelDto dto : Arrays.asList(sql(0,41,null),sql(-1,41,null),sql(771,0,null),
                new ArtikelDto("TEST-A","Test",1,1,null))) {
            assertNull(dto.getSqlPositionsId());
            assertNull(dto.getQuellBlattnummer());
            assertNull(ArtikelMapper.mappe(dto).getSqlPositionsId());
        }
    }

    @Test public void speicherSnapshotErhaeltDieIdentitaetOhneMengeUndNotizZuTeilen() {
        Artikel original = ArtikelMapper.mappe(sql(771,41,0));
        original.setAnmerkung("Notiz");
        Artikel snapshot = ArtikelSnapshots.erstelle(Collections.singletonList(original)).get(0);
        original.setZaehlMenge(9);
        original.setAnmerkung("Geaendert");
        assertEquals(Long.valueOf(771), snapshot.getSqlPositionsId());
        assertEquals(Integer.valueOf(41), snapshot.getQuellBlattnummer());
        assertEquals(Integer.valueOf(0), snapshot.getZaehlMenge());
        assertEquals("Notiz", snapshot.getAnmerkung());
    }
}
