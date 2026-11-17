package com.example.myapplication;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ZaehlmengenSpeicherAntwortTest {
    private ZaehlmengenSpeicherAntwort antwort(int anzahl,int gesamt) {
        Map<Integer,String> offen=new TreeMap<>();
        for(int pos=anzahl+1;pos<=gesamt;pos++) offen.put(pos,"Die Speicherung konnte nicht best\u00e4tigt werden.");
        return new ZaehlmengenSpeicherAntwort(anzahl==gesamt,"SQL-Status 08S01",null)
                .mitMengenbericht(anzahl,gesamt,anzahl,0,offen,false);
    }
    @Test public void zehnVonZehn(){assertEquals("10/10 \u00fcbernommen",antwort(10,10).getBenutzerMeldung());}
    @Test public void fuenfVonZehn(){assertTrue(antwort(5,10).getBenutzerMeldung().startsWith("5/10 \u00fcbernommen"));}
    @Test public void nullVonZehn(){assertTrue(antwort(0,10).getBenutzerMeldung().startsWith("0/10 \u00fcbernommen"));}
    @Test public void keineAenderungIstKeinSchreibvorgang(){assertEquals("Keine neuen \u00c4nderungen vorhanden.",antwort(0,0).getBenutzerMeldung());}
    @Test public void detailsSindVonBenutzertextGetrennt(){
        ZaehlmengenSpeicherAntwort a=antwort(5,10);
        assertFalse(a.getBenutzerMeldung().contains("SQL"));assertEquals("SQL-Status 08S01",a.getMeldung());
        for(int pos=6;pos<=10;pos++)assertTrue(a.getBenutzerMeldung().contains("Position "+pos+" "));
        assertFalse(a.istWiederholbar());
    }
    @Test public void gleicherWertIstKeineNeueSchreiboperation(){
        ZaehlmengenSpeicherAntwort a=new ZaehlmengenSpeicherAntwort(true,"",null)
                .mitMengenbericht(2,2,1,1,Collections.emptyMap(),false);
        assertEquals(2,a.getUebernommen());assertEquals(1,a.getGeschrieben());assertEquals(1,a.getGleicherWert());
        assertTrue(a.getBenutzerMeldung().startsWith("2/2 \u00fcbernommen"));
    }
    @Test public void nichtLesbarerZustandBehauptetKeineAenderungsfreiheit(){
        assertFalse(new ZaehlmengenSpeicherAntwort(false,"Lesefehler",null).getBenutzerMeldung().contains("Keine neuen"));
    }
    @Test public void neueEingabenErhaltenBereitsErreichteZahlenAberSperrenNavigation(){
        ZaehlmengenSpeicherAntwort a=antwort(10,10).mitOffenenEingaben();
        assertFalse(a.istErfolgreich());assertTrue(a.istWiederholbar());
        assertEquals(10,a.getUebernommen());assertEquals(10,a.getGesamt());
        assertTrue(a.getBenutzerMeldung().contains("Neue Eingaben"));
    }

}
