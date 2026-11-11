package com.example.myapplication;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SqlListenAktualisierungTest {
    @Test public void bewahrtUngeklaerteLeereListenUndLokaleDaten() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "listen_aktualisierung_" + UUID.randomUUID() + ".db";
        LokalerInventurSpeicher speicher = new LokalerInventurSpeicher(context, name);
        try {
            speicher.initialisiereZaehlerlisten(Arrays.asList(liste(101), liste(102), liste(103), liste(104)));
            speicher.initialisiereArtikel(102, Collections.singletonList(
                    new ArtikelDto(1, "ART-1", "Erhalten", "R1", 10, 2, 0)));
            speicher.merkeZaehlerlisteFuerSynchronisierung(103);

            speicher.entferneVeralteteLeereZaehlerlisten(Collections.singletonList(104));

            assertNotNull("Ungeklaerter leerer Cache-Eintrag bleibt geschuetzt", speicher.ladeZaehlerlisteFuerId(101));
            assertNotNull("Liste mit lokalen Artikeln", speicher.ladeZaehlerlisteFuerId(102));
            assertEquals(Integer.valueOf(0), speicher.ladeArtikel(102).get(0).getZaehlMenge());
            assertNotNull("Liste mit offener Uebertragung", speicher.ladeZaehlerlisteFuerId(103));
            assertTrue(speicher.hatOffeneSynchronisierungFuerZaehlerliste(103));
            assertNotNull("Aktive leere SQL-Liste", speicher.ladeZaehlerlisteFuerId(104));
        } finally { speicher.close(); context.deleteDatabase(name); }
    }

    @Test public void fehlgeschlagenesRemoteLadenEntferntKeineListen() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "listen_fehler_" + UUID.randomUUID() + ".db";
        LokalerInventurSpeicher speicher = new LokalerInventurSpeicher(context, name);
        try {
            speicher.initialisiereZaehlerlisten(Collections.singletonList(liste(101)));
            speicher.entferneVeralteteLeereZaehlerlisten(null);
            assertNotNull(speicher.ladeZaehlerlisteFuerId(101));
        } finally { speicher.close(); context.deleteDatabase(name); }
    }

    private ZaehlerlisteDto liste(int id) {
        return new ZaehlerlisteDto(id, "Liste " + id, String.valueOf(id), "Bereich", "11.11.2026", true, false);
    }
}
