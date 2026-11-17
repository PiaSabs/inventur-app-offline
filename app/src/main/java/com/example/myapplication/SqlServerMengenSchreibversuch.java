package com.example.myapplication;

import java.math.BigDecimal;
import java.sql.*;
import java.util.Objects;

/** Einmaliger Mengenversuch; weder Warteschlangenverarbeitung noch Auftragsquittung. */
public final class SqlServerMengenSchreibversuch implements EinzelpositionsSynchronisierung.Server {
    public static final String MENGEN_TOKEN = "wert-v1-";
    public interface Verbindung { Connection oeffne() throws SQLException; }
    public enum Status { RUECKGELESEN, KONFLIKT, FEHLER, COMMIT_UNGEKLAERT }
    public static final class Ausgangsstand {
        public final long positionsId, ergebnisId;
        public final int blatt, position;
        public final String artikel;
        public final Timestamp datum, datInventur;
        public final Integer zaehlNr;
        public final BigDecimal menge;
        private Ausgangsstand(ResultSet r) throws SQLException {
            positionsId=r.getLong("PositionsId"); ergebnisId=r.getLong("ErgebnisId");
            blatt=r.getInt("BlattNr"); position=r.getInt("PosNr"); artikel=r.getString("Artikel");
            datum=r.getTimestamp("Datum"); datInventur=r.getTimestamp("DatInventur");
            int nr=r.getInt("ZaehlNr"); zaehlNr=r.wasNull()?null:nr;
            menge=r.getBigDecimal("MengeLager");
            if (positionsId<=0 || ergebnisId<=0 || artikel==null || datum==null || zaehlNr==null || r.getInt("PositionsAnzahl")!=1)
                throw new SQLException("Ergebnisidentitaet unvollstaendig.","23000");
        }
        private Ausgangsstand(EinzelpositionsSynchronisierung.Stand s) {
            String[] teile=s.durchgang.split("\\|",-1);
            if(teile.length!=3)throw new IllegalArgumentException("SQL-Durchgang fehlt.");
            datum=Timestamp.valueOf(teile[0]);zaehlNr=Integer.valueOf(teile[1]);
            datInventur=teile[2].isEmpty()?null:Timestamp.valueOf(teile[2]);
            blatt=s.blatt;position=s.position;positionsId=s.positionsId;ergebnisId=s.ergebnisId;artikel=s.artikel;
            menge=s.menge==null?null:BigDecimal.valueOf(s.menge);
        }
        private Ausgangsstand(Ausgangsstand basis,BigDecimal gelesen) {
            positionsId=basis.positionsId;ergebnisId=basis.ergebnisId;blatt=basis.blatt;position=basis.position;
            artikel=basis.artikel;datum=basis.datum;datInventur=basis.datInventur;zaehlNr=basis.zaehlNr;menge=gelesen;
        }
    }
    public static final class Ergebnis {
        public final Status status;
        public final BigDecimal gelesen;
        public final int betroffeneZeilen, sqlFehler;
        public final String sqlStatus;
        private Ergebnis(Status s, BigDecimal m, int rows, SQLException e) {
            status=s; gelesen=m; betroffeneZeilen=rows;
            sqlFehler=e==null?0:e.getErrorCode(); sqlStatus=e==null?"":e.getSQLState();
        }
        public String meldung() {
            if(status==Status.RUECKGELESEN) return "Einzelmenge in SQL gespeichert und zurueckgelesen. Keine dauerhafte Auftragsbestaetigung; lokale Aenderungen bleiben offen.";
            if(status==Status.KONFLIKT) return "SQL-Konflikt: Ausgangsstand oder Zuordnung geaendert. Lokale Aenderungen bleiben erhalten.";
            if(status==Status.COMMIT_UNGEKLAERT) return "SQL-Commit ungeklärt. Nicht erneut senden; manuelle Pruefung erforderlich. Lokale Aenderungen bleiben offen.";
            return "SQL-Einzelversuch fehlgeschlagen (SQL-Code "+sqlFehler+", Status "+sqlStatus+"). Lokale Aenderungen bleiben erhalten.";
        }
    }
    private final Verbindung quelle;
    private final boolean freigegeben;
    private Ergebnis letztesErgebnis;
    public SqlServerMengenSchreibversuch(Verbindung quelle, boolean freigegeben) {
        if(quelle==null) throw new IllegalArgumentException("Verbindungsquelle fehlt.");
        this.quelle=quelle; this.freigegeben=freigegeben;
    }
    private void pruefe(Connection c) throws SQLException {
        if(!freigegeben || !"DatenWHF".equals(c.getCatalog()) || !c.getAutoCommit())
            throw new SQLException("Einzelversuch nicht freigegeben.","25006");
    }
    private static void testblatt(int blatt,int position) throws SQLException {
        if(blatt<=0 || position<=0) throw new SQLException("Gueltige Blatt- und Positionsnummer erforderlich.","25006");
    }
    private static final String LESEN = "SELECT z.IDSQL AS PositionsId,b.IDSql AS ErgebnisId,z.BlattNr,z.PosNr,z.IdentNr AS Artikel,b.Datum,b.ZaehlNr,b.DatInventur,b.MengeLager,"
            + "(SELECT COUNT(*) FROM dbo.[Z\u00e4hlliste] p WHERE p.BlattNr=z.BlattNr AND p.PosNr=z.PosNr) AS PositionsAnzahl "
            + "FROM dbo.[Z\u00e4hlliste] z JOIN dbo.Bestandszaehlungen b ON b.BlattNr=z.BlattNr AND b.PosNr=z.PosNr "
            + "AND b.IdentNrBestand COLLATE Latin1_General_BIN2=z.IdentNr COLLATE Latin1_General_BIN2 "
            + "AND DATALENGTH(b.IdentNrBestand)=DATALENGTH(z.IdentNr) WHERE z.BlattNr=? AND z.PosNr=?";
    public Ausgangsstand lese(int blatt,int position) throws SQLException {
        testblatt(blatt,position);
        if(!freigegeben) throw new SQLException("Einzelversuch nicht freigegeben.","25006");
        try(Connection c=quelle.oeffne()) { pruefe(c); return lese(c,blatt,position,null); }
    }
    public Ausgangsstand lese(int blatt,int position,String stichtag) throws SQLException {
        testblatt(blatt,position);
        if(!freigegeben)throw new SQLException("Mengenabgleich nicht freigegeben.","25006");
        Timestamp datum;
        try {datum=Timestamp.valueOf(stichtag);}catch(IllegalArgumentException e){throw new SQLException("SQL-Stichtag fehlt.","23000");}
        try(Connection c=quelle.oeffne()){pruefe(c);return lese(c,blatt,position,datum);}
    }
    public Ausgangsstand leseAmStichtag(int blatt,int position,String tag) throws SQLException {
        testblatt(blatt,position);
        Timestamp datum;
        try {datum=Timestamp.valueOf(java.sql.Date.valueOf(tag)+" 00:00:00");}
        catch(IllegalArgumentException e){throw new SQLException("SQL-Stichtag fehlt.","23000");}
        if(!freigegeben)throw new SQLException("SQL-Lesen nicht freigegeben.","25006");
        try(Connection c=quelle.oeffne()) {
            pruefe(c);
            try(PreparedStatement s=c.prepareStatement(LESEN+" AND b.Datum>=? AND b.Datum<DATEADD(day,1,?)")) {
                s.setQueryTimeout(15);s.setInt(1,blatt);s.setInt(2,position);s.setTimestamp(3,datum);s.setTimestamp(4,datum);
                try(ResultSet r=s.executeQuery()) {
                    if(!r.next())throw new SQLException("Keine SQL-Ergebniszeile am Stichtag.","23000");
                    Ausgangsstand stand=new Ausgangsstand(r);
                    if(r.next())throw new SQLException("Mehrere SQL-Ergebniszeilen am Stichtag.","23000");
                    return stand;
                }
            }
        }
    }
    private Ausgangsstand lese(Connection c,int blatt,int position,Timestamp datum) throws SQLException {
        try(PreparedStatement s=c.prepareStatement(LESEN+(datum==null?"":" AND b.Datum=?"))) {
            s.setQueryTimeout(15); s.setInt(1,blatt); s.setInt(2,position);
            if(datum!=null)s.setTimestamp(3,datum);
            try(ResultSet r=s.executeQuery()) {
                if(!r.next()) throw new SQLException("Keine Ergebniszuordnung.","23000");
                Ausgangsstand a=new Ausgangsstand(r);
                if(r.next()) throw new SQLException("Mehrdeutige Ergebniszuordnung.","23000");
                return a;
            }
        }
    }
    private static boolean gleich(BigDecimal a,BigDecimal b) { return a==null?b==null:b!=null && a.compareTo(b)==0; }
    private static boolean gleicheZuordnung(Ausgangsstand a,Ausgangsstand b) {
        return a.positionsId==b.positionsId && a.ergebnisId==b.ergebnisId && a.blatt==b.blatt && a.position==b.position
                && Objects.equals(a.artikel,b.artikel) && Objects.equals(a.datum,b.datum)
                && Objects.equals(a.datInventur,b.datInventur) && Objects.equals(a.zaehlNr,b.zaehlNr);
    }
    private static boolean gleich(Ausgangsstand a,Ausgangsstand b) {
        return gleicheZuordnung(a,b) && gleich(a.menge,b.menge);
    }
    public Ergebnis schreibeEinmal(Ausgangsstand erwartet,Integer menge) {
        if(erwartet==null) throw new IllegalArgumentException("Ausgangsstand fehlt.");
        Connection c=null; boolean commitBegonnen=false,committed=false; int rows=0;
        BigDecimal neu=menge==null?null:BigDecimal.valueOf(menge);
        try {
            testblatt(erwartet.blatt,erwartet.position);
            if(!freigegeben) throw new SQLException("Einzelversuch nicht freigegeben.","25006");
            c=quelle.oeffne(); pruefe(c);
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE); c.setAutoCommit(false);
            Ausgangsstand aktuell=lese(c,erwartet.blatt,erwartet.position,erwartet.datum);
            if(!gleich(erwartet,aktuell)) { c.rollback(); return new Ergebnis(Status.KONFLIKT,null,0,null); }
            if(gleich(erwartet.menge,neu)) { c.rollback(); throw new SQLException("Unveraenderte Menge wird nicht geschrieben.","25000"); }
            // Ergebnis-ID und alter Mengenwert werden in derselben UPDATE-Anweisung geprueft.
            try(PreparedStatement s=c.prepareStatement("UPDATE dbo.Bestandszaehlungen SET MengeLager=? WHERE IDSql=? AND BlattNr=? AND PosNr=? AND (MengeLager=? OR (MengeLager IS NULL AND ? IS NULL))")) {
                s.setQueryTimeout(15); setze(s,1,neu); s.setLong(2,erwartet.ergebnisId); s.setInt(3,erwartet.blatt); s.setInt(4,erwartet.position);
                setze(s,5,erwartet.menge); setze(s,6,erwartet.menge); rows=s.executeUpdate();
            }
            if(rows!=1) { c.rollback(); return new Ergebnis(Status.KONFLIKT,null,rows,null); }
            Ausgangsstand nach=lese(c,erwartet.blatt,erwartet.position,erwartet.datum);
            if(!gleich(nach.menge,neu) || !gleicheZuordnung(nach,erwartet))
                throw new SQLException("Ruecklesen vor Commit fehlgeschlagen.","23000");
            commitBegonnen=true; c.commit(); committed=true;
            // Die gehaltene Verbindung liest nach dem Commit erneut; kein Wiederholungsversuch.
            Ausgangsstand bestaetigt=lese(c,erwartet.blatt,erwartet.position,erwartet.datum);
            if(!gleich(bestaetigt.menge,neu) || !gleicheZuordnung(bestaetigt,erwartet))
                throw new SQLException("Ruecklesen nach Commit nicht eindeutig.","40001");
            c.rollback();
            return new Ergebnis(Status.RUECKGELESEN,bestaetigt.menge,rows,null);
        } catch(SQLException e) {
            boolean rollbackFehler=false;
            if(c!=null && !committed) { try { c.rollback(); } catch(SQLException ignored) { rollbackFehler=true; } }
            return new Ergebnis(commitBegonnen || rollbackFehler ? Status.COMMIT_UNGEKLAERT:Status.FEHLER,null,rows,e);
        } finally { if(c!=null) try { c.close(); } catch(SQLException ignored) { } }
    }
    private static void setze(PreparedStatement s,int i,BigDecimal n) throws SQLException {
        if(n==null) s.setNull(i,Types.DOUBLE); else s.setBigDecimal(i,n);
    }

    public static EinzelpositionsSynchronisierung.Stand alsMengenstand(Ausgangsstand s) {
        Integer menge=s.menge==null?null:s.menge.intValueExact();
        String durchgang=s.datum+"|"+s.zaehlNr+"|"+(s.datInventur==null?"":s.datInventur);
        String daten=s.blatt+"|"+s.position+"|"+s.positionsId+"|"+s.ergebnisId+"|"+s.artikel.length()+":"+s.artikel+"|"+durchgang+"|"+menge;
        StringBuilder token=new StringBuilder(MENGEN_TOKEN);
        try {
            for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(daten.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                token.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        return new EinzelpositionsSynchronisierung.Stand(s.blatt,s.position,s.positionsId,s.ergebnisId,s.artikel,durchgang,token.toString(),menge);
    }
    public static Ausgangsstand ausMengenstand(EinzelpositionsSynchronisierung.Stand s) {
        Ausgangsstand a=new Ausgangsstand(s);
        if(!alsMengenstand(a).equals(s))throw new IllegalArgumentException("SQL-Vergleichsstand widerspruechlich.");
        return a;
    }
    @Override public boolean istIsolierterTestbetrieb(){return false;}
    @Override public boolean istFreigegebenerEinmalbetrieb(){return freigegeben;}
    @Override public boolean hatDauerhafteAuftragsquittung(){return false;}
    @Override public EinzelpositionsSynchronisierung.Quittung sucheAuftrag(String id)throws java.io.IOException {
        throw new java.io.IOException("Keine serverseitige Auftragssuche; kein automatischer Wiederholungsversuch.");
    }
    @Override public EinzelpositionsSynchronisierung.Quittung verarbeite(EinzelpositionsSynchronisierung.Auftrag a)throws java.io.IOException {
        letztesErgebnis=null;
        try {
            Ausgangsstand erwartet=ausMengenstand(a.erwartet);
            if(Objects.equals(a.menge,a.erwartet.menge)) {
                Ausgangsstand aktuell=lese(erwartet.blatt,erwartet.position,erwartet.datum.toString());
                if(!alsMengenstand(aktuell).equals(a.erwartet))return new EinzelpositionsSynchronisierung.Quittung(a,null,true);
                letztesErgebnis=new Ergebnis(Status.RUECKGELESEN,aktuell.menge,0,null);
                return new EinzelpositionsSynchronisierung.Quittung(a,alsMengenstand(aktuell),false);
            }
            letztesErgebnis=schreibeEinmal(erwartet,a.menge);
            if(letztesErgebnis.status==Status.KONFLIKT)return new EinzelpositionsSynchronisierung.Quittung(a,null,true);
            if(letztesErgebnis.status!=Status.RUECKGELESEN)throw new java.io.IOException(letztesErgebnis.meldung());
            // Erst eine neue Verbindung bestaetigt den Stand fuer die lokale Quittierung.
            Ausgangsstand gelesen=lese(erwartet.blatt,erwartet.position,erwartet.datum.toString());
            if(!gleicheZuordnung(erwartet,gelesen) || !gleich(gelesen.menge,letztesErgebnis.gelesen))
                throw new java.io.IOException("Serverstand nach Commit nicht eindeutig; keine Quittierung.");
            return new EinzelpositionsSynchronisierung.Quittung(a,alsMengenstand(gelesen),false);
        }catch(SQLException|IllegalArgumentException|ArithmeticException e){throw new java.io.IOException("SQL-Mengenabgleich fehlgeschlagen; Position bleibt offen.");}
    }
    public Ergebnis letztesErgebnis(){return letztesErgebnis;}
}
