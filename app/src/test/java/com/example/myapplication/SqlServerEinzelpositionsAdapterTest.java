package com.example.myapplication;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import org.junit.Test;
import static com.example.myapplication.EinzelpositionsSynchronisierung.*;
import static org.junit.Assert.*;

public class SqlServerEinzelpositionsAdapterTest {
    private static final String TOKEN="0x0000000000000001:0x0000000000000002";
    private static Stand basis() { return new Stand(41,1,701,90011,"TEST-A","TEST-RUNDE",TOKEN,null); }
    private static Auftrag auftrag(String id,Integer menge) { return new Auftrag(id,basis(),7,menge); }

    @Test public void ergebnisIdUndPositionsIdWerdenGetrenntGebunden() throws Exception {
        JdbcFake db=new JdbcFake(); Auftrag a=auftrag("OP-1",5);
        Quittung q=db.adapter().verarbeite(a);
        assertEquals(90011L,db.parameter.get(2)); assertEquals(701L,db.parameter.get(3));
        assertEquals(a,q.auftrag); assertEquals(Integer.valueOf(5),q.gespeichert.menge);
        assertEquals(1,db.aenderungen); assertEquals(db.verbindungen,db.geschlossen);
    }
    @Test public void nullUndNullmengeWerdenAlsUnterschiedlicheParameterUebertragen() throws Exception {
        JdbcFake db=new JdbcFake(); db.adapter().verarbeite(auftrag("OP-0",0));
        assertNull(db.parameter.get(9)); assertEquals(Types.INTEGER,(int)db.nulltypen.get(9));
        assertEquals(0,db.parameter.get(10));
        Stand ausgang=db.stand;
        db.adapter().verarbeite(new Auftrag("OP-NULL",ausgang,8,null));
        assertNull(db.stand.menge); assertEquals(Types.INTEGER,(int)db.nulltypen.get(10));
    }
    @Test public void bestaetigterAusgangsstandKommtAusDerErgebnisId() throws Exception {
        JdbcFake db=new JdbcFake(); Stand s=db.adapter().ladeAusgangsstand(90011);
        assertEquals(basis(),s); assertEquals(90011L,db.parameter.get(1));
    }
    @Test public void fehlendeErgebnisIdOeffnetKeineVerbindung() {
        JdbcFake db=new JdbcFake();
        try { db.adapter().ladeAusgangsstand(0); fail(); } catch(IllegalArgumentException erwartet) { }
        catch(IOException e) { throw new AssertionError(e); }
        assertEquals(0,db.verbindungen);
    }
    @Test public void positionsIdWirdNichtAlsErgebnisIdAkzeptiert() throws Exception {
        JdbcFake db=new JdbcFake(); Stand falsch=new Stand(41,1,701,701,"TEST-A","TEST-RUNDE",TOKEN,null);
        assertTrue(db.adapter().verarbeite(new Auftrag("OP-1",falsch,7,0)).konflikt);
        assertEquals(0,db.aenderungen);
    }
    @Test public void fehlendeOderAndereErgebniszeileWirdNichtNachtraeglichZugeordnet() {
        JdbcFake db=new JdbcFake();
        try { db.adapter().ladeAusgangsstand(701); fail(); } catch(IOException erwartet) { }
        assertEquals(0,db.aenderungen);
    }
    @Test public void fehlenderVergleichstokenWirdVorJdbcAbgewiesen() {
        JdbcFake db=new JdbcFake();
        Stand falsch=new Stand(41,1,701,90011,"TEST-A","TEST-RUNDE","Unbekannt",null);
        try { db.adapter().verarbeite(new Auftrag("OP-1",falsch,7,0)); fail(); }
        catch(IllegalArgumentException erwartet) { } catch(IOException e) { throw new AssertionError(e); }
        assertEquals(0,db.verbindungen);
    }
    @Test public void konkurrierendeAenderungWirdNichtUeberschrieben() throws Exception {
        JdbcFake db=new JdbcFake(); db.adapter().verarbeite(auftrag("GERAET-A",5));
        Quittung q=db.adapter().verarbeite(auftrag("GERAET-B",9));
        assertTrue(q.konflikt); assertEquals(Integer.valueOf(5),db.stand.menge); assertEquals(1,db.aenderungen);
    }
    @Test public void geaenderteZuordnungsversionFuehrtAuchBeiGleichenWertenZumKonflikt() throws Exception {
        JdbcFake db=new JdbcFake();
        db.stand=new Stand(41,1,701,90011,"TEST-A","TEST-RUNDE",
                "0x0000000000000001:0x0000000000000004",null);
        assertTrue(db.adapter().verarbeite(auftrag("OP-1",0)).konflikt);
        assertEquals(0,db.aenderungen);
    }
    @Test public void identischeKennungLiefertDauerhaftDieselbeAntwort() throws Exception {
        JdbcFake db=new JdbcFake(); Auftrag a=auftrag("OP-1",5);
        Quittung erste=db.adapter().verarbeite(a);
        assertEquals(erste.gespeichert,db.adapter().verarbeite(a).gespeichert);
        assertEquals(erste.gespeichert,db.adapter().sucheAuftrag(a.id).gespeichert);
        assertEquals(1,db.aenderungen); assertEquals(1,db.journal.size());
    }
    @Test public void gleicherSchluesselMitAnderemInhaltWirdAbgewiesen() throws Exception {
        JdbcFake db=new JdbcFake(); db.adapter().verarbeite(auftrag("OP-1",5));
        try { db.adapter().verarbeite(auftrag("OP-1",9)); fail(); } catch(IOException erwartet) { }
        assertEquals(Integer.valueOf(5),db.stand.menge); assertEquals(1,db.journal.size());
    }
    @Test public void journalfehlerRolltImServermodellMengeUndJournalZurueck() throws Exception {
        JdbcFake db=new JdbcFake(); db.journalfehler=true;
        try { db.adapter().verarbeite(auftrag("OP-1",0)); fail(); } catch(IOException erwartet) { }
        assertEquals(basis(),db.stand); assertTrue(db.journal.isEmpty());
    }
    @Test public void abbruchVorCommitKannMitIdentischerKennungWiederholtWerden() throws Exception {
        JdbcFake db=new JdbcFake(); Auftrag a=auftrag("OP-1",0); db.vorCommit=true;
        try { db.adapter().verarbeite(a); fail(); } catch(IOException erwartet) { }
        assertNull(db.adapter().sucheAuftrag(a.id)); assertEquals(basis(),db.stand);
        assertFalse(db.adapter().verarbeite(a).konflikt); assertEquals(1,db.aenderungen);
    }
    @Test public void abbruchNachCommitWirdVomGemeinsamenAblaufOhneZweitesUpdateGeloest() {
        JdbcFake db=new JdbcFake(); LokalFake lokal=new LokalFake(auftrag("OP-1",0)); db.nachCommit=true;
        EinzelpositionsSynchronisierung sync=new EinzelpositionsSynchronisierung(lokal,db.adapter());
        assertEquals(Status.AUSSTEHEND,sync.synchronisiere(41,1)); assertFalse(lokal.quittiert);
        assertEquals(Status.BESTAETIGT,sync.synchronisiere(41,1)); assertTrue(lokal.quittiert);
        assertEquals(1,db.aenderungen); assertEquals(1,db.verarbeitungen);
    }
    @Test public void neuereLokaleRevisionBleibtBeiAlterQuittungOffen() {
        JdbcFake db=new JdbcFake(); LokalFake lokal=new LokalFake(auftrag("OP-1",5));
        db.nachAenderung=()->lokal.revision=8;
        assertEquals(Status.BESTAETIGT,new EinzelpositionsSynchronisierung(lokal,db.adapter()).synchronisiere(41,1));
        assertEquals(7,lokal.quittierteRevision); assertFalse(lokal.quittiert);
    }
    @Test public void fremderKatalogOderFehlenderMarkerErlaubtKeinenRpc() {
        for(boolean katalog:new boolean[]{true,false}) {
            JdbcFake db=new JdbcFake(); if(katalog) db.katalog="FREMDE_DATENBANK"; else db.marker="FALSCH";
            try { db.adapter().verarbeite(auftrag("OP-1",0)); fail(); } catch(IOException erwartet) { }
            assertEquals(0,db.rpc); assertEquals(db.verbindungen,db.geschlossen);
        }
    }
    @Test public void fremdeTransaktionWirdNichtUebernommen() {
        JdbcFake db=new JdbcFake(); db.autoCommit=false;
        try { db.adapter().verarbeite(auftrag("OP-1",0)); fail(); } catch(IOException erwartet) { }
        assertEquals(0,db.rpc);
    }
    @Test public void nichtGanzzahligeFirmenmengenWerdenNichtGerundet() {
        JdbcFake db=new JdbcFake(); db.bruchmenge=true;
        try { db.adapter().ladeAusgangsstand(90011); fail(); } catch(IOException erwartet) { }
        assertEquals(0,db.aenderungen);
    }
    @Test public void fehlendeOderMehrdeutigeQuittungIstKeinErfolg() throws Exception {
        for(boolean fehlt:new boolean[]{true,false}) {
            JdbcFake db=new JdbcFake(); db.keineAntwort=fehlt; db.doppelteAntwort=!fehlt;
            try { db.adapter().verarbeite(auftrag("OP-1",5)); fail(); } catch(IOException erwartet) { }
        }
    }
    @Test public void firmenSchreibsperreBlockiertDenAdapterVorDemRpc() throws Exception {
        JdbcFake db=new JdbcFake();
        SqlServerEinzelpositionsAdapter a=new SqlServerEinzelpositionsAdapter(()->SqlServerSchreibschutz.sichere(db.verbindung()));
        try { a.verarbeite(auftrag("OP-1",0)); fail(); } catch(IOException erwartet) { }
        assertFalse(SqlServerSchreibschutz.istSchreibenErlaubt()); assertEquals(0,db.rpc);
    }

    private static class LokalFake implements Lokal {
        Auftrag auftrag; long revision=7,quittierteRevision; boolean quittiert;
        LokalFake(Auftrag a) { auftrag=a; }
        @Override public Auftrag bereiteMengenauftragVor(int b,int p) { return auftrag; }
        @Override public void merkeMengenversuch(Auftrag a) { }
        @Override public void merkeMengenkonflikt(Auftrag a) { }
        @Override public void quittiereMengenauftrag(Auftrag a,Stand s) {
            quittierteRevision=a.lokaleRevision; quittiert=revision==a.lokaleRevision;
        }
    }

    // Der Fake bildet den Prozedurvertrag ab, nicht die SQL-Server-Sperrimplementierung.
    private static class JdbcFake {
        String katalog=SqlServerEinzelpositionsAdapter.TESTDATENBANK,marker="Inventur-Einzeltest-v1";
        Stand stand=basis(); Map<String,Quittung> journal=new HashMap<>();
        Map<Integer,Object> parameter=new HashMap<>(); Map<Integer,Integer> nulltypen=new HashMap<>();
        boolean autoCommit=true,vorCommit,nachCommit,journalfehler,bruchmenge,keineAntwort,doppelteAntwort;
        int verbindungen,geschlossen,rpc,aenderungen,verarbeitungen;
        Runnable nachAenderung;
        SqlServerEinzelpositionsAdapter adapter() { return new SqlServerEinzelpositionsAdapter(this::verbindung); }
        Connection verbindung() {
            verbindungen++;
            return proxy(Connection.class,(name,args)->{
                if(name.equals("getCatalog")) return katalog;
                if(name.equals("getAutoCommit")) return autoCommit;
                if(name.equals("close")) { geschlossen++; return null; }
                if(name.equals("prepareStatement")) return anweisung(false,(String)args[0]);
                if(name.equals("prepareCall")) { rpc++; return anweisung(true,(String)args[0]); }
                return null;
            });
        }
        Object anweisung(boolean call,String sql) {
            Map<Integer,Object> werte=new HashMap<>();
            Handler h=(name,args)->{
                if(name.equals("setString") || name.equals("setInt") || name.equals("setLong")) {
                    werte.put((Integer)args[0],args[1]); return null;
                }
                if(name.equals("setNull")) { werte.put((Integer)args[0],null); nulltypen.put((Integer)args[0],(Integer)args[1]); return null; }
                if(name.equals("executeQuery")) {
                    parameter=werte;
                    if(!call) return zeilen(Collections.singletonList(Collections.singletonMap("1",marker)));
                    if(sql.contains("LeseErgebnis")) return zeilen(Collections.singletonList(standZeile()));
                    Quittung q;
                    if(sql.contains("LeseAuftrag")) q=journal.get((String)werte.get(1));
                    else q=verarbeite(werte);
                    List<Map<String,Object>> rows=new ArrayList<>();
                    if(q!=null && !keineAntwort) { rows.add(quittungZeile(q)); if(doppelteAntwort) rows.add(quittungZeile(q)); }
                    return zeilen(rows);
                }
                return null;
            };
            return call?proxy(CallableStatement.class,h):proxy(PreparedStatement.class,h);
        }
        Quittung verarbeite(Map<Integer,Object> p) throws SQLException {
            verarbeitungen++;
            Stand erwartet=new Stand((Integer)p.get(4),(Integer)p.get(5),(Long)p.get(3),(Long)p.get(2),
                    (String)p.get(6),(String)p.get(7),(String)p.get(8),(Integer)p.get(9));
            Auftrag a=new Auftrag((String)p.get(1),erwartet,(Long)p.get(11),(Integer)p.get(10));
            Quittung alt=journal.get(a.id);
            if(alt!=null) { if(!a.equals(alt.auftrag)) throw new SQLException("Anderer Inhalt", "",51002); return alt; }
            boolean konflikt=!erwartet.equals(stand);
            String neuerToken=String.format(Locale.ROOT,"0x%016X:0x0000000000000002",
                    Long.parseUnsignedLong(stand.token.substring(2,18),16)+1);
            Stand neu=konflikt?null:new Stand(stand.blatt,stand.position,stand.positionsId,stand.ergebnisId,
                    stand.artikel,stand.durchgang,neuerToken,a.menge);
            Quittung q=new Quittung(a,neu,konflikt);
            if(journalfehler || vorCommit) { vorCommit=false; throw new SQLException("Testrollback"); }
            journal.put(a.id,q); if(!konflikt) { stand=neu; aenderungen++; }
            if(nachAenderung!=null) { Runnable r=nachAenderung; nachAenderung=null; r.run(); }
            if(nachCommit) { nachCommit=false; throw new SQLException("Testabbruch nach Commit"); }
            return q;
        }
        Map<String,Object> standZeile() {
            Map<String,Object> r=new HashMap<>();
            r.put("BlattNr",stand.blatt); r.put("PosNr",stand.position); r.put("PositionsId",stand.positionsId);
            r.put("ErgebnisId",stand.ergebnisId); r.put("Artikel",stand.artikel); r.put("Durchgang",stand.durchgang);
            r.put("Token",stand.token); r.put("Menge",bruchmenge?new BigDecimal("0.5"):stand.menge); return r;
        }
        Map<String,Object> quittungZeile(Quittung q) {
            Map<String,Object> r=new HashMap<>(); Auftrag a=q.auftrag; Stand s=a.erwartet;
            r.put("AuftragId",a.id); r.put("BlattNr",s.blatt); r.put("PosNr",s.position); r.put("PositionsId",s.positionsId);
            r.put("ErgebnisId",s.ergebnisId); r.put("Artikel",s.artikel); r.put("Durchgang",s.durchgang);
            r.put("ErwartetesToken",s.token); r.put("ErwarteteMenge",s.menge); r.put("NeueMenge",a.menge);
            r.put("LokaleRevision",a.lokaleRevision); r.put("Status",q.konflikt?"KONFLIKT":"BESTAETIGT");
            r.put("GespeichertesToken",q.gespeichert==null?null:q.gespeichert.token);
            r.put("GespeicherteMenge",q.gespeichert==null?null:q.gespeichert.menge); return r;
        }
        ResultSet zeilen(List<? extends Map<String,?>> rows) {
            int[] index={-1};
            return proxy(ResultSet.class,(name,args)->{
                if(name.equals("next")) return ++index[0]<rows.size();
                if(name.startsWith("get") && args!=null && args.length==1) {
                    Object v=rows.get(index[0]).get(String.valueOf(args[0]));
                    if(name.equals("getString")) return v==null?null:v.toString();
                    if(name.equals("getBigDecimal")) return v==null?null:new BigDecimal(v.toString());
                    if(name.equals("getLong")) return v==null?0L:((Number)v).longValue();
                    if(name.equals("getInt")) return v==null?0:((Number)v).intValue();
                }
                return null;
            });
        }
    }
    private interface Handler { Object run(String name,Object[] args) throws Throwable; }
    private static <T> T proxy(Class<T> typ,Handler handler) {
        return typ.cast(Proxy.newProxyInstance(typ.getClassLoader(),new Class<?>[]{typ},(p,m,a)->{
            Object result=handler.run(m.getName(),a);
            if(result==null && m.getReturnType()==boolean.class) return false;
            if(result==null && m.getReturnType()==int.class) return 0;
            return result;
        }));
    }
}
