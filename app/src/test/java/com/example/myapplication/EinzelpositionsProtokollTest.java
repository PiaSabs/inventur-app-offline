package com.example.myapplication;

import java.io.IOException;
import org.junit.Test;
import static com.example.myapplication.EinzelpositionsSynchronisierung.*;
import static org.junit.Assert.*;

public class EinzelpositionsProtokollTest {
    private final Stand basis=new Stand(41,1,701,90011,"TEST-A","TEST-RUNDE","v1",null);
    private final Auftrag auftrag=new Auftrag("TEST-AUFTRAG",basis,7,0);

    private static class LokalFake implements Lokal {
        Auftrag offen;
        boolean quittiert,konflikt;
        int versuche;
        LokalFake(Auftrag a) { offen=a; }
        @Override public Auftrag bereiteMengenauftragVor(int blatt,int pos) { return offen; }
        @Override public void merkeMengenversuch(Auftrag a) { versuche++; }
        @Override public void quittiereMengenauftrag(Auftrag a,Stand s) { quittiert=true; }
        @Override public void merkeMengenkonflikt(Auftrag a) { konflikt=true; }
    }
    private static class ServerFake implements Server {
        Quittung gefunden,antwort;
        boolean lesefehler,schreibfehler,isoliert=true;
        int schreibaufrufe;
        @Override public boolean istIsolierterTestbetrieb() { return isoliert; }
        @Override public Quittung sucheAuftrag(String id) throws IOException {
            if(lesefehler) throw new IOException("Testabruf fehlgeschlagen"); return gefunden;
        }
        @Override public Quittung verarbeite(Auftrag a) throws IOException {
            schreibaufrufe++; if(schreibfehler) throw new IOException("Testantwort fehlt"); return antwort;
        }
    }
    private Stand stand(String token,Integer menge) { return new Stand(41,1,701,90011,"TEST-A","TEST-RUNDE",token,menge); }
    private Status run(LokalFake l,ServerFake s) { return new EinzelpositionsSynchronisierung(l,s).synchronisiere(41,1); }

    @Test public void korrekteQuittungBestaetigtDieAngefragteRevision() {
        LokalFake l=new LokalFake(auftrag); ServerFake s=new ServerFake();
        s.antwort=new Quittung(auftrag,stand("v2",0),false);
        assertEquals(Status.BESTAETIGT,run(l,s)); assertTrue(l.quittiert);
    }
    @Test public void unveraenderterArtikelWirdNichtUebertragen() {
        ServerFake s=new ServerFake(); assertEquals(Status.NICHTS_ZU_TUN,run(new LokalFake(null),s));
        assertEquals(0,s.schreibaufrufe);
    }
    @Test public void vorhandeneDauerhafteQuittungErfordertKeinErneutesSchreiben() {
        LokalFake l=new LokalFake(auftrag); ServerFake s=new ServerFake();
        s.gefunden=new Quittung(auftrag,stand("v2",0),false);
        assertEquals(Status.BESTAETIGT,run(l,s)); assertEquals(0,s.schreibaufrufe); assertEquals(0,l.versuche);
    }
    @Test public void fehlendeServerantwortQuittiertNichts() {
        LokalFake l=new LokalFake(auftrag); assertEquals(Status.AUSSTEHEND,run(l,new ServerFake()));
        assertFalse(l.quittiert);
    }
    @Test public void fremdeAuftragskennungIstKeineBestaetigung() {
        LokalFake l=new LokalFake(auftrag); ServerFake s=new ServerFake();
        s.antwort=new Quittung(new Auftrag("FREMD",basis,7,0),stand("v2",0),false);
        assertEquals(Status.AUSSTEHEND,run(l,s)); assertFalse(l.quittiert);
    }
    @Test public void fremdeLokaleRevisionIstKeineBestaetigung() {
        LokalFake l=new LokalFake(auftrag); ServerFake s=new ServerFake();
        s.antwort=new Quittung(new Auftrag(auftrag.id,basis,8,0),stand("v2",0),false);
        assertEquals(Status.AUSSTEHEND,run(l,s)); assertFalse(l.quittiert);
    }
    @Test public void falscheErgebniszuordnungWirdNichtQuittiert() {
        LokalFake l=new LokalFake(auftrag); ServerFake s=new ServerFake();
        s.antwort=new Quittung(auftrag,new Stand(41,1,701,90012,"TEST-A","TEST-RUNDE","v2",0),false);
        assertEquals(Status.AUSSTEHEND,run(l,s)); assertFalse(l.quittiert);
    }
    @Test public void unveraenderterServerTokenIstKeineSchreibbestaetigung() {
        LokalFake l=new LokalFake(auftrag); ServerFake s=new ServerFake();
        s.antwort=new Quittung(auftrag,stand("v1",0),false);
        assertEquals(Status.AUSSTEHEND,run(l,s)); assertFalse(l.quittiert);
    }
    @Test public void nullUndNullmengeWerdenBeiDerQuittierungNichtGleichgesetzt() {
        LokalFake l=new LokalFake(auftrag); ServerFake s=new ServerFake();
        s.antwort=new Quittung(auftrag,stand("v2",null),false);
        assertEquals(Status.AUSSTEHEND,run(l,s)); assertFalse(l.quittiert);
    }
    @Test public void konkurrierendeAenderungBleibtAlsKonfliktOffen() {
        LokalFake l=new LokalFake(auftrag); ServerFake s=new ServerFake();
        s.antwort=new Quittung(auftrag,null,true);
        assertEquals(Status.KONFLIKT,run(l,s)); assertTrue(l.konflikt); assertFalse(l.quittiert);
    }
    @Test public void nichtErreichbareAuftragspruefungVerhindertErneutesSchreiben() {
        LokalFake l=new LokalFake(auftrag); ServerFake s=new ServerFake(); s.lesefehler=true;
        assertEquals(Status.AUSSTEHEND,run(l,s)); assertEquals(0,s.schreibaufrufe); assertFalse(l.quittiert);
    }
    @Test public void verbindungsabbruchLaesstDieRevisionOffen() {
        LokalFake l=new LokalFake(auftrag); ServerFake s=new ServerFake(); s.schreibfehler=true;
        assertEquals(Status.AUSSTEHEND,run(l,s)); assertFalse(l.quittiert);
    }
    @Test public void nichtIsolierterServerWirdAbgewiesen() {
        ServerFake s=new ServerFake(); s.isoliert=false;
        try { new EinzelpositionsSynchronisierung(new LokalFake(auftrag),s); fail("Keine Firmenfreigabe"); }
        catch(IllegalArgumentException erwartet) { assertEquals(0,s.schreibaufrufe); }
    }
    @Test public void fehlendeErgebnisOderDurchgangsidentitaetIstUngueltig() {
        for(int fall=0;fall<2;fall++) {
            try { new Stand(41,1,701,fall==0?0:90011,"TEST-A",fall==1?"":"TEST-RUNDE","v1",null); fail(); }
            catch(IllegalArgumentException erwartet) { }
        }
    }
}
