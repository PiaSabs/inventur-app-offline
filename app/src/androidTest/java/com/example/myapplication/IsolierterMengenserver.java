package com.example.myapplication;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.io.IOException;
import org.json.JSONObject;
import static com.example.myapplication.EinzelpositionsSynchronisierung.*;

// Ausschliesslich synthetischer Testserver; keine JDBC-Verbindung oder Firmenkonfiguration.
class IsolierterMengenserver extends SQLiteOpenHelper implements Server {
    boolean abbruchVorCommit, abbruchNachCommit, abfrageNichtErreichbar;
    Runnable nachCommit;
    int verarbeitungen;

    IsolierterMengenserver(Context c, String name) { super(c, name, null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE ergebnis(id INTEGER PRIMARY KEY, blatt INTEGER, pos INTEGER, "
                + "positions_id INTEGER, artikel TEXT, durchgang TEXT, token INTEGER, menge INTEGER, "
                + "UNIQUE(blatt,durchgang,positions_id))");
        db.execSQL("CREATE TABLE quittung(id TEXT PRIMARY KEY, payload TEXT NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int a, int b) { throw new AssertionError("Kein Testserver-Upgrade"); }
    @Override public boolean istIsolierterTestbetrieb() { return true; }

    void seed(Stand s) {
        ContentValues v=new ContentValues();
        v.put("id",s.ergebnisId); v.put("blatt",s.blatt); v.put("pos",s.position);
        v.put("positions_id",s.positionsId); v.put("artikel",s.artikel); v.put("durchgang",s.durchgang);
        v.put("token",Long.parseLong(s.token)); v.put("menge",s.menge);
        getWritableDatabase().insertOrThrow("ergebnis",null,v);
    }

    Stand leseStand() {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM ergebnis ORDER BY id",null)) {
            if(!c.moveToFirst()) return null;
            return new Stand(c.getInt(1),c.getInt(2),c.getLong(3),c.getLong(0),c.getString(4),
                    c.getString(5),c.getString(6),c.isNull(7)?null:c.getInt(7));
        }
    }

    long anzahl(String tabelle) {
        if(!"ergebnis".equals(tabelle) && !"quittung".equals(tabelle)) throw new AssertionError();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM "+tabelle,null)) {
            c.moveToFirst(); return c.getLong(0);
        }
    }

    @Override public synchronized Quittung sucheAuftrag(String id) throws IOException {
        if(abfrageNichtErreichbar) throw new IOException("Isolierter Abruffehler");
        try(Cursor c=getReadableDatabase().rawQuery("SELECT payload FROM quittung WHERE id=?",new String[]{id})) {
            return c.moveToFirst()?decode(c.getString(0)):null;
        }
    }

    @Override public synchronized Quittung verarbeite(Auftrag a) throws IOException {
        verarbeitungen++;
        SQLiteDatabase db=getWritableDatabase();
        Quittung antwort;
        db.beginTransaction();
        try {
            Quittung vorhanden=sucheAuftrag(a.id);
            if(vorhanden!=null) {
                if(!a.equals(vorhanden.auftrag)) throw new IllegalArgumentException("Auftragskennung mit anderem Inhalt");
                db.setTransactionSuccessful(); return vorhanden;
            }
            Stand vorher=leseStand();
            ContentValues v=new ContentValues(); v.put("menge",a.menge);
            v.put("token",vorher==null?1:Long.parseLong(vorher.token)+1);
            Stand e=a.erwartet;
            int anzahl=db.update("ergebnis",v,"id=? AND blatt=? AND pos=? AND positions_id=? "
                    + "AND artikel=? AND durchgang=? AND token=? AND menge IS ?",
                    new String[]{String.valueOf(e.ergebnisId),String.valueOf(e.blatt),String.valueOf(e.position),
                            String.valueOf(e.positionsId),e.artikel,e.durchgang,e.token,e.menge==null?null:e.menge.toString()});
            antwort=new Quittung(a,anzahl==1?leseStand():null,anzahl!=1);
            ContentValues quittung=new ContentValues(); quittung.put("id",a.id); quittung.put("payload",encode(antwort));
            db.insertOrThrow("quittung",null,quittung);
            if(abbruchVorCommit) { abbruchVorCommit=false; throw new IOException("Abbruch vor Commit"); }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        if(nachCommit!=null) { Runnable aktion=nachCommit; nachCommit=null; aktion.run(); }
        if(abbruchNachCommit) { abbruchNachCommit=false; throw new IOException("Abbruch nach Commit"); }
        return antwort;
    }

    private static JSONObject stand(Stand s) throws Exception {
        return new JSONObject().put("blatt",s.blatt).put("pos",s.position).put("pid",s.positionsId)
                .put("eid",s.ergebnisId).put("artikel",s.artikel).put("runde",s.durchgang)
                .put("token",s.token).put("menge",s.menge==null?JSONObject.NULL:s.menge);
    }
    private static Stand stand(JSONObject j) throws Exception {
        return new Stand(j.getInt("blatt"),j.getInt("pos"),j.getLong("pid"),j.getLong("eid"),
                j.getString("artikel"),j.getString("runde"),j.getString("token"),j.isNull("menge")?null:j.getInt("menge"));
    }
    private static String encode(Quittung q) {
        try {
            Auftrag a=q.auftrag;
            return new JSONObject().put("id",a.id).put("erwartet",stand(a.erwartet)).put("rev",a.lokaleRevision)
                    .put("menge",a.menge==null?JSONObject.NULL:a.menge).put("konflikt",q.konflikt)
                    .put("stand",q.gespeichert==null?JSONObject.NULL:stand(q.gespeichert)).toString();
        } catch(Exception e) { throw new AssertionError(e); }
    }
    private static Quittung decode(String text) {
        try {
            JSONObject j=new JSONObject(text);
            Auftrag a=new Auftrag(j.getString("id"),stand(j.getJSONObject("erwartet")),j.getLong("rev"),
                    j.isNull("menge")?null:j.getInt("menge"));
            return new Quittung(a,j.isNull("stand")?null:stand(j.getJSONObject("stand")),j.getBoolean("konflikt"));
        } catch(Exception e) { throw new AssertionError(e); }
    }
}
