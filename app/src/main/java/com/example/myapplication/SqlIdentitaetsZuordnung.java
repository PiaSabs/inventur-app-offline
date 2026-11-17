package com.example.myapplication;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.*;

/** Ein Vorschlag ist keine Mengenquittung und kein automatisch bewiesener Durchgang. */
public final class SqlIdentitaetsZuordnung {
    public static final class Lokal {
        public final int blatt, position;
        public final String artikel, stichtag, quellStichtag, fingerprint;
        public final Long positionsId;
        public final Integer quellBlatt, menge;
        public Lokal(int blatt,int position,String artikel,String stichtag,String quellStichtag,
                Long positionsId,Integer quellBlatt,Integer menge,String fingerprint) {
            this.blatt=blatt;this.position=position;this.artikel=artikel;this.stichtag=stichtag;
            this.quellStichtag=quellStichtag;this.positionsId=positionsId;this.quellBlatt=quellBlatt;
            this.menge=menge;this.fingerprint=fingerprint;
        }
    }
    public static final class Vorschlag {
        public final Lokal lokal;
        public final SqlServerMengenSchreibversuch.Ausgangsstand server;
        public final boolean stichtagGleich;
        private Vorschlag(Lokal l,SqlServerMengenSchreibversuch.Ausgangsstand s) {
            lokal=l;server=s;stichtagGleich=tag(l.stichtag).equals(tag(s.datum.toString()));
        }
        public String beschreibung() {
            return "Blatt "+lokal.blatt+", Position "+lokal.position+"\nArtikelidentitaet stimmt exakt ueberein."
                    +"\nSQL-Positions-ID: "+server.positionsId+"\nSQL-Ergebnis-ID: "+server.ergebnisId
                    +"\nLokaler Stichtag: "+lokal.stichtag+"\nSQL-Stichtag: "+server.datum
                    +"\nSQL-Zaehlnummer: "+server.zaehlNr+"\nLokale Menge: "+lokal.menge+"\nAktuelle SQL-Menge: "+server.menge
                    +"\n\n"+(stichtagGleich?"Der Stichtag stimmt ueberein; die Durchgangszuordnung ist fachlich noch zu bestaetigen.":"Stichtag abweichend oder unbekannt. Durchgang ungeklärt; keine Schreibfreigabe.")
                    +"\nIdentitaeten ergaenzen bestaetigt keine Mengenuebertragung. Mengen, Notizen, Revisionen und Auftraege bleiben erhalten.";
        }
    }
    public static Vorschlag pruefe(Lokal l,List<SqlPosition> positionen,SqlServerMengenSchreibversuch.Ausgangsstand s) {
        if(l==null || l.blatt<=0 || l.position<=0 || l.artikel==null || l.artikel.isEmpty())
            throw new IllegalArgumentException("Blatt, Position oder Artikelidentitaet fehlt.");
        if(positionen==null || s==null) throw new IllegalArgumentException("SQL-Ergebniszuordnung fehlt.");
        Set<Long> ids=new HashSet<>();SqlPosition treffer=null;int anzahl=0;
        for(SqlPosition p:positionen) {
            if(p==null || p.id==null || p.id<=0 || p.blatt==null || p.position==null || p.artikelnummer==null || !ids.add(p.id))
                throw new IllegalArgumentException("SQL-Positionsidentitaet fehlt oder ist doppelt.");
            if(p.blatt==l.blatt && p.position==l.position) {treffer=p;anzahl++;}
        }
        if(anzahl!=1 || !l.artikel.equals(treffer.artikelnummer) || s.blatt!=l.blatt || s.position!=l.position
                || !l.artikel.equals(s.artikel) || s.positionsId!=treffer.id || s.ergebnisId<=0 || s.datum==null || s.zaehlNr==null)
            throw new IllegalArgumentException("Blatt, Position, Artikel oder Ergebniszuordnung widerspruechlich.");
        if((l.positionsId!=null && l.positionsId!=s.positionsId) || (l.quellBlatt!=null && l.quellBlatt!=s.blatt)
                || (l.quellStichtag!=null && !l.quellStichtag.isEmpty() && !tag(l.quellStichtag).equals(tag(s.datum.toString()))))
            throw new IllegalArgumentException("Vorhandene lokale Identitaet widerspricht dem Vorschlag.");
        return new Vorschlag(l,s);
    }
    public static void pruefeBestaetigung(Vorschlag alt,Vorschlag frisch,boolean durchgangBestaetigt) {
        if(alt==null || frisch==null || !Objects.equals(alt.lokal.fingerprint,frisch.lokal.fingerprint)
                || !gleicheServerIdentitaet(alt.server,frisch.server))
            throw new IllegalArgumentException("Zuordnung oder lokaler Stand inzwischen geaendert. Vorschlag erneut pruefen.");
        if(durchgangBestaetigt && !frisch.stichtagGleich)
            throw new IllegalArgumentException("Abweichender oder fehlender Stichtag; Durchgang bleibt ungeklärt.");
    }
    public static boolean gleicheServerIdentitaet(SqlServerMengenSchreibversuch.Ausgangsstand a,SqlServerMengenSchreibversuch.Ausgangsstand b) {
        return a.positionsId==b.positionsId && a.ergebnisId==b.ergebnisId && a.blatt==b.blatt && a.position==b.position
                && Objects.equals(a.artikel,b.artikel) && Objects.equals(a.datum,b.datum)
                && Objects.equals(a.zaehlNr,b.zaehlNr) && Objects.equals(a.datInventur,b.datInventur);
    }
    public static boolean stichtageGleich(String lokal,String server) {
        String a=tag(lokal);return !a.isEmpty() && a.equals(tag(server));
    }
    static String tag(String text) {
        if(text==null || text.isEmpty())return "";
        String date=text.matches("\\d{4}-\\d{2}-\\d{2}( .*)?")?text.substring(0,10):text;
        for(String format:new String[]{"yyyy-MM-dd","dd.MM.yyyy"}) {
            SimpleDateFormat f=new SimpleDateFormat(format,Locale.ROOT);f.setLenient(false);
            ParsePosition p=new ParsePosition(0);java.util.Date d=f.parse(date,p);
            if(d!=null && p.getIndex()==date.length())return new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(d);
        }
        return "";
    }
}
