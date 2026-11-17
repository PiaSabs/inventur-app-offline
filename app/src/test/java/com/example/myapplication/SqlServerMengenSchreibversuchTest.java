package com.example.myapplication;

import org.junit.Test;
import java.lang.reflect.*;
import java.sql.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.Assert.*;
import static com.example.myapplication.SqlServerMengenSchreibversuch.*;

public class SqlServerMengenSchreibversuchTest {
    @Test public void einzelmengeWirdBedingtGeschriebenUndNachCommitGelesen() throws Exception {
        Db d=new Db(); Ausgangsstand a=d.api().lese(99001,1);
        Ergebnis e=d.api().schreibeEinmal(a,7);
        assertEquals(Status.RUECKGELESEN,e.status); assertEquals(new BigDecimal("7"),e.gelesen);
        assertEquals(1,d.updates); assertEquals(1,d.commits); assertEquals(1,e.betroffeneZeilen);
        assertTrue(d.updateSql.contains("IDSql=?")); assertTrue(d.updateSql.contains("MengeLager IS NULL"));
        assertEquals(22L,d.parameters.get(2)); assertEquals(99001,d.parameters.get(3));
    }
    @Test public void nullUndNullmengeBleibenVerschieden() throws Exception {
        Db d=new Db(); assertNull(d.value); assertEquals(Status.RUECKGELESEN,d.api().schreibeEinmal(d.api().lese(99001,1),0).status);
        assertEquals(BigDecimal.ZERO,d.value);
        assertEquals(Status.RUECKGELESEN,d.api().schreibeEinmal(d.api().lese(99001,1),null).status); assertNull(d.value);
    }
    @Test public void geaenderterAusgangsstandBleibtErhalten() throws Exception {
        Db d=new Db(); Ausgangsstand a=d.api().lese(99001,1); d.value=BigDecimal.TEN;
        assertEquals(Status.KONFLIKT,d.api().schreibeEinmal(a,7).status); assertEquals(0,d.updates); assertEquals(BigDecimal.TEN,d.value);
    }
    @Test public void parallelesUpdateWirdNichtUeberschrieben() throws Exception {
        Db d=new Db(); Ausgangsstand a=d.api().lese(99001,1); d.affected=0;
        assertEquals(Status.KONFLIKT,d.api().schreibeEinmal(a,7).status); assertEquals(0,d.commits); assertNull(d.value);
    }
    @Test public void unveraenderteMengeWirdNichtGeschrieben() throws Exception {
        Db d=new Db(); Ausgangsstand a=d.api().lese(99001,1);
        assertEquals(Status.FEHLER,d.api().schreibeEinmal(a,null).status); assertEquals(0,d.updates);
    }
    @Test public void ungueltigeBlaetterWerdenVorVerbindungGesperrt() throws Exception {
        Db d=new Db(); try {d.api().lese(0,1); fail();} catch(SQLException e) {assertEquals("25006",e.getSQLState());} assertEquals(0,d.opens);
    }
    @Test public void fehlendeFreigabeVerhindertVerbindung() throws Exception {
        Db d=new Db(); try {new SqlServerMengenSchreibversuch(d::open,false).lese(99001,1); fail();} catch(SQLException expected) {} assertEquals(0,d.opens);
    }
    @Test public void falscherKatalogWirdGesperrt() throws Exception {
        Db d=new Db(); d.catalog="Andere"; try {d.api().lese(99001,1); fail();} catch(SQLException expected) {} assertEquals(0,d.updates);
    }
    @Test public void doppelteZuordnungErlaubtKeinenSchreibversuch() throws Exception {
        Db d=new Db(); Ausgangsstand a=d.api().lese(99001,1); d.duplicate=true;
        assertEquals(Status.FEHLER,d.api().schreibeEinmal(a,7).status); assertEquals(0,d.updates);
    }
    @Test public void sqlFehlerWirdOhneTreibertextGemeldet() throws Exception {
        Db d=new Db(); Ausgangsstand a=d.api().lese(99001,1); d.writeError=true;
        Ergebnis e=d.api().schreibeEinmal(a,7); assertEquals(Status.FEHLER,e.status); assertEquals(2627,e.sqlFehler);
        assertFalse(e.meldung().contains("VERTRAULICH")); assertNull(d.value); assertEquals(0,d.commits);
    }
    @Test public void commitAntwortverlustWirdNieAutomatischWiederholt() throws Exception {
        Db d=new Db(); Ausgangsstand a=d.api().lese(99001,1); d.commitError=true;
        Ergebnis e=d.api().schreibeEinmal(a,7); assertEquals(Status.COMMIT_UNGEKLAERT,e.status);
        assertEquals(1,d.updates); assertEquals(1,d.commits); assertEquals(new BigDecimal("7"),d.value);
    }
    @Test public void fehlerhafteRueckleseRolltVorCommitZurueck() throws Exception {
        Db d=new Db(); Ausgangsstand a=d.api().lese(99001,1); d.badRead=true;
        assertEquals(Status.FEHLER,d.api().schreibeEinmal(a,7).status); assertEquals(0,d.commits); assertNull(d.value);
    }
    @Test public void einzelversuchErzeugtKeineAuftragsbestaetigung() throws Exception {
        Db d=new Db(); Ergebnis e=d.api().schreibeEinmal(d.api().lese(99001,1),7);
        assertTrue(e.meldung().contains("Keine dauerhafte Auftragsbestaetigung"));
        assertFalse(d.updateSql.contains("DatInventur=")); assertFalse(d.updateSql.contains("INSERT"));
    }
    @Test public void veraenderteArtikelzuordnungBeiRuecklesenWirdZurueckgerollt() throws Exception {
        Db d=new Db(); Ausgangsstand a=d.api().lese(99001,1); d.identityAfterUpdate=true;
        assertEquals(Status.FEHLER,d.api().schreibeEinmal(a,7).status); assertNull(d.value); assertEquals(0,d.commits);
    }
    @Test public void ruecklesefehlerNachCommitErzeugtKeineQuittierungOderWiederholung() throws Exception {
        Db d=new Db(); Ausgangsstand a=d.api().lese(99001,1); d.postCommitReadError=true;
        assertEquals(Status.COMMIT_UNGEKLAERT,d.api().schreibeEinmal(a,7).status);
        assertEquals(1,d.commits); assertEquals(1,d.updates); assertEquals(new BigDecimal("7"),d.value);
    }
    @Test public void buildFreigabeGiltNurFuerInternalSql() {
        assertEquals("internalSql".equals(BuildConfig.BUILD_TYPE) && "DatenWHF".equals(BuildConfig.SQL_SERVER_DATABASE)
                && "dbo".equals(BuildConfig.SQL_SERVER_SCHEMA),SqlServerSchreibschutz.istKontrollierterEinzelversuchErlaubt());
        assertFalse(SqlServerSchreibschutz.istSchreibenErlaubt());
    }
    static final class Db {
        BigDecimal value,pending; boolean newConnectionReadError,changeOnNewConnection,transaction,duplicate,writeError,commitError,badRead,identityAfterUpdate,postCommitReadError;
        int updates,commits,opens,affected=1,positionCount=1; String catalog="DatenWHF",updateSql,readSql;
        Map<Integer,Object> readParameters;
        Map<Integer,Object> parameters;
        SqlServerMengenSchreibversuch api() {return new SqlServerMengenSchreibversuch(this::open,true);}
        Connection open() {
            opens++;
            transaction=false;
            if(changeOnNewConnection && commits>0) value=BigDecimal.valueOf(1234);
            return proxy(Connection.class,(p,m,a)->{
                switch(m.getName()) {
                    case "getCatalog": return catalog;
                    case "getAutoCommit": return !transaction;
                    case "setAutoCommit": transaction=!(Boolean)a[0]; pending=value; return null;
                    case "prepareStatement": return statement((String)a[0]);
                    case "commit": commits++; value=pending; if(commitError) throw new SQLException("VERTRAULICH","08006"); return null;
                    case "rollback": pending=value; return null;
                    case "close": transaction=false; return null;
                    default:return defaultValue(m.getReturnType());
                }
            });
        }
        PreparedStatement statement(String sql) {
            Map<Integer,Object> params=new HashMap<>();
            return proxy(PreparedStatement.class,(p,m,a)->{
                if(m.getName().startsWith("set") && a!=null && a.length==2) { params.put((Integer)a[0],m.getName().equals("setNull")?null:a[1]); return null; }
                if(m.getName().equals("executeUpdate")) {
                    updates++; updateSql=sql; parameters=params;
                    if(writeError) throw new SQLException("VERTRAULICH","23000",2627);
                    if(affected==1) pending=badRead?new BigDecimal("999"):(BigDecimal)params.get(1);
                    return affected;
                }
                if(m.getName().equals("executeQuery")) {
                    readSql=sql;readParameters=params;
                    if(newConnectionReadError && opens>=3 && commits>0) throw new SQLException("VERTRAULICH","08006");
                    if(postCommitReadError && commits>0) throw new SQLException("VERTRAULICH","08006");
                    return rows();
                }
                return defaultValue(m.getReturnType());
            });
        }
        ResultSet rows() {
            final int[] index={0}; final boolean[] wasNull={false};
            return proxy(ResultSet.class,(p,m,a)->{
                if(m.getName().equals("next")) return ++index[0]<=(duplicate?2:1);
                if(m.getName().equals("wasNull")) return wasNull[0];
                if(m.getName().startsWith("get")) {
                    Object value=null;
                    switch((String)a[0]) {
                        case "PositionsId": value=11L; break; case "ErgebnisId":value=22L;break;
                        case "BlattNr":value=99001;break; case "PosNr":value=1;break;case "Artikel":value=identityAfterUpdate && updates>0?"SYNTH-B":"SYNTH-A";break;
                        case "ZaehlNr":value=1;break;
                        case "PositionsAnzahl":value=positionCount;break;
                        case "Datum":case "DatInventur":value=Timestamp.valueOf("2020-01-01 00:00:00");break;
                        case "MengeLager":value=transaction?pending:this.value;break;
                    }
                    wasNull[0]=value==null; return value==null && m.getReturnType().isPrimitive()?defaultValue(m.getReturnType()):value;
                }
                return defaultValue(m.getReturnType());
            });
        }
    }
    static Object defaultValue(Class<?> c) {if(c==boolean.class)return false;if(c==int.class)return 0;if(c==long.class)return 0L;return null;}
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> c,InvocationHandler h) {return (T)Proxy.newProxyInstance(c.getClassLoader(),new Class<?>[]{c},h);}
}
