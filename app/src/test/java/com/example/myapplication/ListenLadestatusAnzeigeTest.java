package com.example.myapplication;

import org.junit.Test;
import java.time.Instant;
import java.util.TimeZone;
import static org.junit.Assert.assertEquals;

public class ListenLadestatusAnzeigeTest {
    @Test public void uhrzeitVerwendetDieAngegebeneZeitzone() {
        long zeit = Instant.parse("2026-01-15T07:05:00Z").toEpochMilli();
        assertEquals("08:05", ListenLadestatusAnzeige.formatiereUhrzeit(zeit, TimeZone.getTimeZone("Europe/Berlin")));
    }

    @Test public void sommerzeitWirdBeruecksichtigt() {
        long zeit = Instant.parse("2026-07-15T07:05:00Z").toEpochMilli();
        assertEquals("09:05", ListenLadestatusAnzeige.formatiereUhrzeit(zeit, TimeZone.getTimeZone("Europe/Berlin")));
    }

    @Test public void stundenUndMinutenWerdenZweistelligAngezeigt() {
        long zeit = Instant.parse("2026-01-15T00:04:00Z").toEpochMilli();
        assertEquals("00:04", ListenLadestatusAnzeige.formatiereUhrzeit(zeit, TimeZone.getTimeZone("UTC")));
    }
}
