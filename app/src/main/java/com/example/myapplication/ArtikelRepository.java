package com.example.myapplication;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

// Das Repository kapselt die Herkunft der Artikeldaten.
// Die Activity kennt dadurch nur fachliche Methoden und muss spaeter nicht
// umgebaut werden, wenn statt Mock-Daten eine echte Schnittstelle genutzt wird.
public class ArtikelRepository {

    private static final long REMOTE_FEHLER_COOLDOWN_MS = 15_000L;

    private final InventurRemoteDataSource remoteDataSource;
    private final LokalerInventurSpeicher lokalerInventurSpeicher;
    private final LongSupplier uhr;
    private volatile Long letzterErfolgreicherListenabrufMillis;
    private boolean remoteBestandSynchronisiert;
    private boolean automatischeSynchronisierungPausiert;
    private String letzteSynchronisierungsFehlermeldung = "";
    private long naechsterRemoteVersuchAbMillis;

    public ArtikelRepository(InventurRemoteDataSource remoteDataSource, LokalerInventurSpeicher lokalerInventurSpeicher) {
        this(remoteDataSource, lokalerInventurSpeicher, System::currentTimeMillis);
    }

    ArtikelRepository(InventurRemoteDataSource remoteDataSource, LokalerInventurSpeicher lokalerInventurSpeicher,
            LongSupplier uhr) {
        this.remoteDataSource = remoteDataSource;
        this.lokalerInventurSpeicher = lokalerInventurSpeicher;
        this.uhr = uhr;
    }

    public List<Zaehlerliste> ladeZaehlerlisten() {
        initialisiereOfflineBestandFallsNoetig();
        return lokalerInventurSpeicher.ladeZaehlerlisten();
    }

    public Listenuebersicht aktualisiereListenuebersicht() {
        if (remoteDataSource instanceof MockInventurRemoteDataSource) {
            return Listenuebersicht.lokal(ladeZaehlerlisten());
        }
        try {
            List<ZaehlerlisteDto> server = remoteDataSource.ladeListenuebersicht();
            Listenuebersicht stand = Listenuebersicht.vomServer(lokalerInventurSpeicher.ladeZaehlerlisten(), server);
            // Erst die vollstaendige und eindeutige Listenantwort gilt als erfolgreicher Abruf.
            long abrufzeit = uhr.getAsLong();
            letzterErfolgreicherListenabrufMillis = abrufzeit;
            return stand.mitAbrufzeit(abrufzeit);
        } catch (RemoteDatenException | IllegalArgumentException exception) {
            return Listenuebersicht.lokal(lokalerInventurSpeicher.ladeZaehlerlisten(), letzterErfolgreicherListenabrufMillis);
        }
    }

    public Long holeLetztenErfolgreichenListenabrufMillis() {
        return letzterErfolgreicherListenabrufMillis;
    }

    public Zaehlerliste ladeZaehlerlisteFuerId(int zaehlerlistenId) {
        initialisiereOfflineBestandFallsNoetig();
        return lokalerInventurSpeicher.ladeZaehlerlisteFuerId(zaehlerlistenId);
    }

    public List<Artikel> ladeArtikel(int zaehlerlistenId) {
        initialisiereOfflineBestandFallsNoetig();
        return lokalerInventurSpeicher.ladeArtikel(zaehlerlistenId);
    }

    public void ladeSqlBlattFallsFehlt(int blatt) throws RemoteDatenException {
        if(!(remoteDataSource instanceof ApiInventurRemoteDataSource))return;
        if(lokalerInventurSpeicher.ladeZaehlerlisteFuerId(blatt)!=null && !lokalerInventurSpeicher.ladeArtikel(blatt).isEmpty())return;
        ZaehlerlisteDto kopf=null;
        for(ZaehlerlisteDto k:remoteDataSource.ladeZaehlerlisten())if(k.getId()==blatt) {
            if(kopf!=null)throw new RemoteDatenException("SQL-Blattnummer mehrfach vorhanden.");kopf=k;
        }
        if(kopf==null)throw new RemoteDatenException("SQL-Blatt nicht vorhanden.");
        List<ArtikelDto> artikel=remoteDataSource.ladeArtikel(blatt);
        lokalerInventurSpeicher.speichereErstdownload(kopf,artikel);
    }

    public SqlPositionsvergleich pruefeSqlPositionen(int blattId) {
        try {
            List<SqlPosition> server = remoteDataSource.ladeSqlPositionen(blattId);
            // Erst nach dem Abruf lesen, damit inzwischen gespeicherte Aenderungen erhalten bleiben.
            return SqlPositionsvergleich.vergleiche(blattId, lokalerInventurSpeicher.ladeArtikel(blattId), server);
        } catch (RemoteDatenException | RuntimeException exception) {
            return SqlPositionsvergleich.nichtVerfuegbar();
        }
    }

    EinzelpositionsSynchronisierung.Status synchronisiereEinzelmengeIsoliert(int blatt, int position,
            EinzelpositionsSynchronisierung.Server isolierterServer) {
        return new EinzelpositionsSynchronisierung(lokalerInventurSpeicher, isolierterServer).synchronisiere(blatt, position);
    }

    public ZaehlmengenSpeicherAntwort speichereZaehlmengen(int zaehlerlistenId, String zaehlerName, String schreiberName, List<Artikel> artikelListe) {
        return speichereZaehlmengenMitSynchronisierung(zaehlerlistenId, zaehlerName, schreiberName, artikelListe);
    }

    public ZaehlmengenSpeicherAntwort speichereZaehlmengenNurLokal(int zaehlerlistenId, String zaehlerName, String schreiberName, List<Artikel> artikelListe) {
        if (!istSpeicherAnfrageGueltig(zaehlerlistenId, artikelListe)) {
            return neueLeereSpeicherAntwort();
        }

        lokalerInventurSpeicher.speichereZaehlmengen(
                zaehlerlistenId,
                zaehlerName,
                schreiberName,
                artikelListe
        );
        return new ZaehlmengenSpeicherAntwort(
                true,
                "Lokal gespeichert.",
                null
        );
    }

    public ZaehlmengenSpeicherAntwort speichereZaehlmengenMitSynchronisierung(int zaehlerlistenId, String zaehlerName, String schreiberName, List<Artikel> artikelListe) {
        if (!istSpeicherAnfrageGueltig(zaehlerlistenId, artikelListe)) {
            return neueLeereSpeicherAntwort();
        }

        ZaehlmengenSpeicherAnfrage anfrage = ZaehlmengenSpeicherMapper.mappe(
                zaehlerlistenId,
                zaehlerName,
                schreiberName,
                artikelListe
        );

        lokalerInventurSpeicher.speichereZaehlmengen(
                zaehlerlistenId,
                zaehlerName,
                schreiberName,
                artikelListe
        );

        if (istSqlSchreibenGesperrt()) {
            return new ZaehlmengenSpeicherAntwort(false, "Lokal gespeichert. " + SqlServerSchreibschutz.MELDUNG, null);
        }

        if (!darfRemoteVersuchtWerden()) {
            return new ZaehlmengenSpeicherAntwort(
                    true,
                    "Lokal gespeichert. Datenbank-Synchronisierung ist weiterhin offen und wird spaeter erneut versucht.",
                    null
            );
        }

        try {
            ZaehlmengenSpeicherAntwort antwort = remoteDataSource.speichereZaehlmengen(anfrage);
            if (antwort.istErfolgreich()) {
                merkeRemoteErfolg();
                lokalerInventurSpeicher.entferneZaehlerlisteAusSynchronisierung(zaehlerlistenId);
                synchronisiereAusstehendeAenderungen(true);
                if (!lokalerInventurSpeicher.hatOffeneSynchronisierung()) {
                    letzteSynchronisierungsFehlermeldung = "";
                }
                return new ZaehlmengenSpeicherAntwort(
                        true,
                        "Lokal gespeichert und an die Datenbank uebertragen. " + antwort.getMeldung(),
                        antwort.getReferenzId()
                );
            }
            letzteSynchronisierungsFehlermeldung = antwort.getMeldung();
            return new ZaehlmengenSpeicherAntwort(
                    true,
                    "Lokal gespeichert. Die Datenbank-Synchronisierung ist offen und wird spaeter erneut versucht.",
                    antwort.getReferenzId()
            );
        } catch (RemoteDatenException exception) {
            merkeRemoteFehlschlag();
            letzteSynchronisierungsFehlermeldung = baueSynchronisierungsFehlermeldung(exception);
            return new ZaehlmengenSpeicherAntwort(
                    true,
                    "Lokal gespeichert. Datenbank aktuell nicht erreichbar. Synchronisierung wird spaeter automatisch nachgeholt.",
                    null
            );
        }
    }

    public boolean markiereZaehlerlisteAlsAbgeschlossen(int zaehlerlistenId) {
        if (zaehlerlistenId <= 0) {
            return false;
        }

        boolean lokalAktualisiert = lokalerInventurSpeicher.markiereZaehlerlisteAlsAbgeschlossen(zaehlerlistenId);
        if (!lokalAktualisiert) {
            return false;
        }

        if (darfRemoteVersuchtWerden()) {
            synchronisiereAusstehendeAenderungen(true);
        }
        return lokalAktualisiert;
    }

    public boolean markiereZaehlerlisteAlsOffen(int zaehlerlistenId) {
        if (zaehlerlistenId <= 0) {
            return false;
        }
        return lokalerInventurSpeicher.markiereZaehlerlisteAlsOffen(zaehlerlistenId);
    }


    public void angleicheBeispielWochenlistenZuordnungFuerTest(String zaehlerName, String schreiberName) {
        if (remoteDataSource instanceof MockInventurRemoteDataSource) {
            lokalerInventurSpeicher.angleicheBeispielWochenlistenZuordnungFuerTest(zaehlerName, schreiberName);
        }
    }

    public boolean hatOffeneSynchronisierung() {
        return lokalerInventurSpeicher.hatOffeneSynchronisierung();
    }

    public boolean istHauptmenueRueckkehrErlaubt() {
        return !hatOffeneMengensynchronisierung(null);
    }

    public boolean hatOffeneMengensynchronisierung(Integer blattId) {
        try {
            return lokalerInventurSpeicher.hatOffeneMengensynchronisierung(blattId);
        } catch (RuntimeException exception) {
            // Ein unlesbarer lokaler Stand ist keine Serverbestaetigung.
            return true;
        }
    }

    public Synchronisierungsdiagnose holeSynchronisierungsdiagnose(Integer blattId) {
        try {
            return lokalerInventurSpeicher.ladeSynchronisierungsdiagnose(blattId);
        } catch (RuntimeException exception) {
            return Synchronisierungsdiagnose.nichtVerfuegbar();
        }
    }

    public boolean istSqlSchreibenGesperrt() {
        return remoteDataSource.istSchreibgeschuetzt();
    }

    public SqlServerMengenSchreibversuch.Ausgangsstand leseKontrolliertenMengenstand(int blatt, int position)
            throws java.sql.SQLException {
        if (!(remoteDataSource instanceof ApiInventurRemoteDataSource)) throw new java.sql.SQLException("Keine SQL-Datenquelle.");
        SqlIdentitaetsZuordnung.Lokal lokal=lokalerInventurSpeicher.ladeLokaleIdentitaetsposition(blatt,position);
        SqlServerMengenSchreibversuch server=((ApiInventurRemoteDataSource) remoteDataSource).kontrollierterMengenversuch();
        return lokal.quellStichtag==null || lokal.quellStichtag.isEmpty()?server.leseAmStichtag(blatt,position,SqlIdentitaetsZuordnung.tag(lokal.stichtag)):server.lese(blatt,position,lokal.quellStichtag);
    }

    public boolean istSqlMengenabgleichVerfuegbar() {
        return remoteDataSource instanceof ApiInventurRemoteDataSource && SqlServerSchreibschutz.istKontrollierterEinzelversuchErlaubt();
    }

    public static final class MengenPlan {
        public final SqlServerMengenSchreibversuch.Ausgangsstand server;
        public final Integer menge;
        final String fingerprint;
        MengenPlan(SqlServerMengenSchreibversuch.Ausgangsstand server,Integer menge,String fingerprint) {
            this.server=server;this.menge=menge;this.fingerprint=fingerprint;
        }
    }
    public static final class MengenListe {
        public final int blatt;
        public final List<MengenPlan> positionen;
        public final List<String> probleme;
        public final List<SqlIdentitaetsZuordnung.Vorschlag> zuordnungen = new ArrayList<>();
        final java.util.Map<Integer,String> benutzerGruende = new java.util.TreeMap<>();
        boolean wiederholbar;
        MengenListe(int blatt,List<MengenPlan> positionen,List<String> probleme) {
            this.blatt=blatt;this.positionen=java.util.Collections.unmodifiableList(positionen);this.probleme=java.util.Collections.unmodifiableList(probleme);
        }
    }
    public static final class MengenAntwort {
        public final EinzelpositionsSynchronisierung.Status status;
        public final String meldung;
        public final int sqlZeilen;
        String benutzerGrund;
        MengenAntwort(EinzelpositionsSynchronisierung.Status status,String meldung,int sqlZeilen) {this.status=status;this.meldung=meldung;this.sqlZeilen=sqlZeilen;
            benutzerGrund=status==EinzelpositionsSynchronisierung.Status.KONFLIKT
                    ? "Der Eintrag wurde zwischenzeitlich ver\u00e4ndert."
                    : status==EinzelpositionsSynchronisierung.Status.ZUORDNUNG_UNGEKLAERT
                    ? "Der Artikel konnte nicht eindeutig zugeordnet werden."
                    : "Die Speicherung konnte nicht best\u00e4tigt werden.";}
    }

    public MengenPlan bereiteEinfachenMengenabgleichVor(SqlServerMengenSchreibversuch.Ausgangsstand stand) throws java.sql.SQLException {
        SqlServerSchreibschutz.pruefeKontrolliertenEinzelversuch();
        lokalerInventurSpeicher.pruefeEinfachenMengenstand(stand);
        SqlIdentitaetsZuordnung.Lokal lokal=lokalerInventurSpeicher.ladeLokaleIdentitaetsposition(stand.blatt,stand.position);
        return new MengenPlan(stand,lokal.menge,lokal.fingerprint);
    }


    public MengenListe bereiteGeaenderteMengenVor(int blatt) {
        List<SqlIdentitaetsZuordnung.Vorschlag> zuordnungen = new ArrayList<>();
        List<MengenPlan> plaene = new ArrayList<>();
        List<String> probleme = new ArrayList<>();
        java.util.Map<Integer,String> gruende = new java.util.TreeMap<>();
        boolean wiederholbar=false;
        for (int position : lokalerInventurSpeicher.ladeGeaenderteMengenpositionen(blatt)) {
            try {
                SqlServerMengenSchreibversuch.Ausgangsstand stand = leseKontrolliertenMengenstand(blatt, position);
                if (!lokalerInventurSpeicher.istSqlDurchgangBestaetigt(stand)) {
                    SqlIdentitaetsZuordnung.Vorschlag v = pruefeMengenidentitaet(stand);
                    // Technische Identitaet ist keine fachliche Freigabe und keine Mengenquittung.
                    lokalerInventurSpeicher.bestaetigeSqlIdentitaet(v, false);
                    v = pruefeMengenidentitaet(stand);
                    zuordnungen.add(v);
                    gruende.put(position,"Es ist noch unklar, zu welcher Z\u00e4hlung die Menge geh\u00f6rt.");
                    probleme.add("Position " + position + ": Durchgang ungeklärt; keine Mengenübertragung.");
                    continue;
                }
                plaene.add(bereiteEinfachenMengenabgleichVor(stand));
            } catch (Exception exception) {
                probleme.add("Position " + position + ": " + mengenFehler(exception));
                gruende.put(position,benutzerMengenfehler(exception));
                wiederholbar |= istVerbindungsfehler(exception);
            }
        }
        MengenListe ergebnis = new MengenListe(blatt, plaene, probleme);
        ergebnis.zuordnungen.addAll(zuordnungen);
        ergebnis.benutzerGruende.putAll(gruende);
        ergebnis.wiederholbar=wiederholbar;
        return ergebnis;
    }

    private SqlIdentitaetsZuordnung.Vorschlag pruefeMengenidentitaet(
            SqlServerMengenSchreibversuch.Ausgangsstand stand) throws RemoteDatenException {
        List<SqlPosition> passend = new ArrayList<>();
        for (SqlPosition position : remoteDataSource.ladeSqlPositionen(stand.blatt)) {
            if (position != null && ((position.blatt != null && position.blatt == stand.blatt
                    && position.position != null && position.position == stand.position)
                    || (position.id != null && position.id == stand.positionsId))) passend.add(position);
        }
        return SqlIdentitaetsZuordnung.pruefe(
                lokalerInventurSpeicher.ladeLokaleIdentitaetsposition(stand.blatt, stand.position), passend, stand);
    }

    public void bestaetigeMengenidentitaet(SqlIdentitaetsZuordnung.Vorschlag v)
            throws java.sql.SQLException, RemoteDatenException {
        SqlIdentitaetsZuordnung.Vorschlag frisch = pruefeMengenidentitaet(
                leseKontrolliertenMengenstand(v.lokal.blatt, v.lokal.position));
        SqlIdentitaetsZuordnung.pruefeBestaetigung(v, frisch, true);
        lokalerInventurSpeicher.bestaetigeSqlIdentitaet(frisch, true);
    }

    public MengenAntwort synchronisiereEinzelmengeEinfach(MengenPlan plan) {
        int position=plan.server.position;
        try {
            if(!(remoteDataSource instanceof ApiInventurRemoteDataSource))throw new IllegalArgumentException("Keine SQL-Datenquelle.");
            SqlServerMengenSchreibversuch server=((ApiInventurRemoteDataSource)remoteDataSource).kontrollierterMengenversuch();
            lokalerInventurSpeicher.legeEinfachenMengenauftragAn(plan.server,plan.fingerprint,plan.menge);
            EinzelpositionsSynchronisierung.Status status=new EinzelpositionsSynchronisierung(lokalerInventurSpeicher,server)
                    .synchronisiere(plan.server.blatt,position);
            SqlServerMengenSchreibversuch.Ergebnis sql=server.letztesErgebnis();
            String text;
            if(status==EinzelpositionsSynchronisierung.Status.BESTAETIGT)text="Menge "+plan.menge+" serverseitig bestaetigt; lokale Quittierung revisionsbezogen ausgefuehrt.";
            else if(status==EinzelpositionsSynchronisierung.Status.KONFLIKT)text="SQL-Konflikt; lokale Menge bleibt offen.";
            else if(status==EinzelpositionsSynchronisierung.Status.ZUORDNUNG_UNGEKLAERT)text="Zuordnung oder Durchgang ungeklärt; nichts quittiert.";
            else text="Versuch nicht eindeutig abgeschlossen oder lokale Revision geaendert. Keine automatische Wiederholung; nichts quittiert."
                    +(sql==null?"":" SQL-Code "+sql.sqlFehler+", Status "+sql.sqlStatus+".");
            letzteSynchronisierungsFehlermeldung="Position "+position+": "+text;
            MengenAntwort antwort=new MengenAntwort(status,letzteSynchronisierungsFehlermeldung,sql==null?0:sql.betroffeneZeilen);
            if(sql!=null && sql.status==SqlServerMengenSchreibversuch.Status.COMMIT_UNGEKLAERT)
                antwort.benutzerGrund="Die Speicherung konnte nicht best\u00e4tigt werden. Bitte von der IT pr\u00fcfen lassen.";
            else if(sql!=null && sql.sqlStatus!=null && sql.sqlStatus.startsWith("08"))
                antwort.benutzerGrund="Die Verbindung wurde unterbrochen. Die Speicherung konnte nicht best\u00e4tigt werden.";
            return antwort;
        }catch(Exception e) {
            String text="Position "+position+": "+mengenFehler(e);letzteSynchronisierungsFehlermeldung=text;
            MengenAntwort antwort=new MengenAntwort(EinzelpositionsSynchronisierung.Status.ZUORDNUNG_UNGEKLAERT,text,0);
            antwort.benutzerGrund=benutzerMengenfehler(e);
            return antwort;
        }
    }

    public String synchronisiereMengenliste(MengenListe liste) {
        return synchronisiereMengenlisteErgebnis(liste).getMeldung();
    }

    private ZaehlmengenSpeicherAntwort synchronisiereMengenlisteErgebnis(MengenListe liste) {
        java.util.Set<Integer> vorher=new java.util.TreeSet<>(lokalerInventurSpeicher.ladeOffeneMengenpositionen(liste.blatt));
        java.util.Set<Integer> bearbeitet=new java.util.HashSet<>();
        java.util.Map<Integer,String> gruende=new java.util.TreeMap<>(liste.benutzerGruende);
        StringBuilder details=new StringBuilder();
        java.util.Set<Integer> bestaetigt=new java.util.HashSet<>();
        int geschrieben=0, gleicherWert=0;
        for(MengenPlan plan:liste.positionen) {
            int pos=plan.server.position;
            if(!vorher.contains(pos) || !bearbeitet.add(pos)) continue;
            MengenAntwort antwort=synchronisiereEinzelmengeEinfach(plan);
            if(antwort.status==EinzelpositionsSynchronisierung.Status.BESTAETIGT) {
                if(antwort.sqlZeilen==1) geschrieben++; else gleicherWert++;
                bestaetigt.add(pos);
            } else gruende.put(pos,antwort.benutzerGrund);
            details.append(antwort.meldung).append('\n');
        }
        for(String problem:liste.probleme) details.append(problem).append('\n');
        java.util.Set<Integer> nachher=new java.util.TreeSet<>(lokalerInventurSpeicher.ladeOffeneMengenpositionen(liste.blatt));
        gruende.keySet().retainAll(nachher);
        for(int pos:bestaetigt) if(nachher.contains(pos)) {
            gruende.put(pos,"Auf dem Tablet gibt es inzwischen eine weitere Änderung. Diese ist noch nicht übernommen.");
            liste.wiederholbar=true;
        }
        for(int pos:nachher) gruende.putIfAbsent(pos,"Die Speicherung konnte nicht best\u00e4tigt werden. Bitte pr\u00fcfen lassen.");
        java.util.Set<Integer> unklar=new java.util.HashSet<>(lokalerInventurSpeicher.ladeUngeklaerteMengenversuche(liste.blatt));
        for(int pos:unklar) {
            gruende.put(pos,"Die Speicherung konnte nicht bestätigt werden. Bitte von der IT prüfen lassen.");
            details.append("Position ").append(pos).append(": Auftragsstatus UNKLAR; keine automatische Wiederholung.\n");
        }
        details.insert(0,"Erfolgreich \u00fcbertragene Mengen: "+geschrieben
                +"\nBereits gleicher SQL-Wert best\u00e4tigt: "+gleicherWert+"\n\n");
        details.append(beschreibeOffeneEinzelfelder(liste.blatt));
        boolean offen=hatOffeneMengensynchronisierung(liste.blatt);
        if(istHauptmenueRueckkehrErlaubt()) details.append("\nHauptmen\u00fc freigegeben.");
        else details.append("\nHauptmen\u00fc bleibt gesperrt: unbest\u00e4tigte Mengen oder ungekl\u00e4rte Mengen\u00fcbertragungen.");
        return new ZaehlmengenSpeicherAntwort(!offen,details.toString(),null)
                .mitMengenbericht(geschrieben+gleicherWert,vorher.size(),geschrieben,gleicherWert,gruende,
                        liste.wiederholbar && !unklar.containsAll(nachher));
    }

    private static boolean istVerbindungsfehler(Exception e) {
        if(!(e instanceof java.sql.SQLException)) return false;
        String status=((java.sql.SQLException)e).getSQLState();
        return status!=null && (status.startsWith("08") || status.equals("HYT00") || status.equals("HYT01"));
    }

    private static String benutzerMengenfehler(Exception e) {
        if(istVerbindungsfehler(e)) return "Die Verbindung konnte nicht hergestellt oder aufrechterhalten werden.";
        if(e instanceof IllegalArgumentException) {
            String text=e.getMessage();
            if(text!=null && text.startsWith("SQL-Konflikt:")) return "Der Eintrag wurde zwischenzeitlich verändert.";
            if(text!=null && text.startsWith("Frueherer Versuch")) return "Eine frühere Speicherung ist noch ungeklärt. Bitte von der IT prüfen lassen.";
            return "Der Artikel oder die Zählung konnte nicht eindeutig zugeordnet werden. Bitte prüfen lassen.";
        }
        if(e instanceof java.sql.SQLException && "23000".equals(((java.sql.SQLException)e).getSQLState()))
            return "Die passende Zählung konnte nicht eindeutig gefunden werden.";
        return "Die Menge konnte nicht vorbereitet werden. Bitte prüfen lassen.";
    }

    private static String mengenFehler(Exception e) {
        if(e instanceof IllegalArgumentException)return e.getMessage();
        if(e instanceof java.sql.SQLException) {java.sql.SQLException s=(java.sql.SQLException)e;return "SQL-Fehler "+s.getErrorCode()+", Status "+s.getSQLState()+"; nichts quittiert.";}
        return "Zuordnung oder Serverstand nicht verfuegbar; lokale Aenderung bleibt offen.";
    }





    public String beschreibeOffeneEinzelfelder(int blatt) {
        return lokalerInventurSpeicher.beschreibeOffeneEinzelfelder(blatt);
    }

    public ZaehlmengenSpeicherAntwort synchronisiereBeimVerlassen(int blatt, boolean abschliessen) {
        try {
            // Abschluss lokal vormerken; der Mengenweg bestaetigt keinen Abschluss.
            if (abschliessen && !lokalerInventurSpeicher.markiereZaehlerlisteAlsAbgeschlossen(blatt)) {
                return new ZaehlmengenSpeicherAntwort(false, "Abschluss konnte nicht lokal gespeichert werden.", null);
            }
            if(istSqlMengenabgleichVerfuegbar()) {
                return synchronisiereMengenlisteErgebnis(bereiteGeaenderteMengenVor(blatt));
            }
            java.util.Map<Integer,String> gruende=new java.util.TreeMap<>();
            for(int pos:lokalerInventurSpeicher.ladeOffeneMengenpositionen(blatt))
                gruende.put(pos,"Die Mengen\u00fcbertragung ist in dieser App-Version nicht verf\u00fcgbar.");
            return new ZaehlmengenSpeicherAntwort(!hatOffeneMengensynchronisierung(blatt),
                    "SQL-Mengen\u00fcbertragung ist in diesem Modus nicht verf\u00fcgbar.\n"+beschreibeOffeneEinzelfelder(blatt),null)
                    .mitMengenbericht(0,gruende.size(),0,0,gruende,false);
        } catch (RuntimeException exception) {
            return new ZaehlmengenSpeicherAntwort(false,
                    "Synchronisierung nicht abgeschlossen. Lokale Änderungen bleiben erhalten; SQL und lokalen Speicher prüfen.", null);
        }
    }


    public boolean hatOffeneSynchronisierungFuerZaehlerliste(int zaehlerlistenId) {
        return lokalerInventurSpeicher.hatOffeneSynchronisierungFuerZaehlerliste(zaehlerlistenId);
    }

    public void setzeAutomatischeSynchronisierungPausiert(boolean pausiert) {
        automatischeSynchronisierungPausiert = pausiert;
    }


    public String holeLetzteSynchronisierungsFehlermeldung() {
        if (istSqlSchreibenGesperrt()) {
            return SqlServerSchreibschutz.MELDUNG
                    + (letzteSynchronisierungsFehlermeldung == null || letzteSynchronisierungsFehlermeldung.isEmpty()
                    ? "" : "\n\n" + letzteSynchronisierungsFehlermeldung);
        }
        String diagnose = remoteDataSource.holeLetzteDiagnose();
        boolean hatFehlermeldung = letzteSynchronisierungsFehlermeldung != null
                && !letzteSynchronisierungsFehlermeldung.trim().isEmpty();
        boolean hatDiagnose = diagnose != null && !diagnose.trim().isEmpty();

        if (!hatFehlermeldung && !hatDiagnose) {
            return "Die genaue Ursache konnte noch nicht ermittelt werden.";
        }
        if (!hatFehlermeldung) {
            return diagnose;
        }
        if (!hatDiagnose) {
            return letzteSynchronisierungsFehlermeldung;
        }
        return letzteSynchronisierungsFehlermeldung + "\n\nDiagnose:\n" + diagnose;
    }

    public void pruefeDatenbankverbindungUndSynchronisiereBeiBedarf() {
        if (automatischeSynchronisierungPausiert || !AppContainer.istApiModusAktiv() || !darfRemoteVersuchtWerden()) {
            return;
        }

        try {
            remoteDataSource.pruefeVerbindung();
            merkeRemoteErfolg();

            if (!lokalerInventurSpeicher.hatZaehlerlisten()) {
                initialisiereOfflineBestandFallsNoetig();
                if (!lokalerInventurSpeicher.hatOffeneSynchronisierung()) {
                    letzteSynchronisierungsFehlermeldung = "";
                }
                return;
            }

            synchronisiereAusstehendeAenderungen();
            if (!lokalerInventurSpeicher.hatOffeneSynchronisierung()) {
                letzteSynchronisierungsFehlermeldung = "";
            }
        } catch (RemoteDatenException exception) {
            merkeRemoteFehlschlag();
            letzteSynchronisierungsFehlermeldung = baueSynchronisierungsFehlermeldung(exception);
        }
    }

    public void pruefeDatenbankverbindungUndSynchronisiereSofort() {
        naechsterRemoteVersuchAbMillis = 0L;
        pruefeDatenbankverbindungUndSynchronisiereBeiBedarf();
    }

    public void verarbeiteNetzwerkWiederVerfuegbar() {
        if (automatischeSynchronisierungPausiert || !AppContainer.istApiModusAktiv()) {
            return;
        }
        pruefeDatenbankverbindungUndSynchronisiereBeiBedarf();
    }

    private synchronized void initialisiereOfflineBestandFallsNoetig() {
        boolean apiModusAktiv = !(remoteDataSource instanceof MockInventurRemoteDataSource);
        if (!apiModusAktiv) {
            lokalerInventurSpeicher.initialisiereBeispieldatenFuerHilfstabellenFallsLeer();
        }
        boolean hatLokaleZaehlerlisten = lokalerInventurSpeicher.hatZaehlerlisten();

        if (apiModusAktiv && hatLokaleZaehlerlisten && !automatischeSynchronisierungPausiert) {
            synchronisiereAusstehendeAenderungen();
        }
        if (apiModusAktiv && hatLokaleZaehlerlisten
                && (remoteBestandSynchronisiert || automatischeSynchronisierungPausiert
                || lokalerInventurSpeicher.hatOffeneSynchronisierung() || !darfRemoteVersuchtWerden())) {
            return;
        }

        if (!apiModusAktiv && hatLokaleZaehlerlisten) {
            if (remoteDataSource instanceof MockInventurRemoteDataSource) {
                MockInventurRemoteDataSource demo = (MockInventurRemoteDataSource) remoteDataSource;
                for (ZaehlerlisteDto demoListe : demo.ladeZaehlerlisten()) {
                    Zaehlerliste lokal = lokalerInventurSpeicher.ladeZaehlerlisteFuerId(demoListe.getId());
                    if (lokal != null && demoListe.getBezeichnung().equals(lokal.getBezeichnung())) {
                        lokalerInventurSpeicher.ergaenzeDemoArtikel(demoListe.getId(),
                                demo.ladeArtikel(demoListe.getId()));
                    }
                }
            }
            return;
        }

        try {
            List<ZaehlerlisteDto> zaehlerlisten = remoteDataSource.ladeZaehlerlisten();
            Map<Integer, List<ArtikelDto>> artikelNachZaehlerlistenId = new LinkedHashMap<>();
            for (ZaehlerlisteDto zaehlerliste : zaehlerlisten) {
                artikelNachZaehlerlistenId.put(
                        zaehlerliste.getId(),
                        remoteDataSource.ladeArtikel(zaehlerliste.getId())
                );
            }

            // Beim Start aktualisieren, ohne lokale Statusdaten zu loeschen.
            // Offene lokale Mengen sind oben vom Ueberschreiben ausgeschlossen.
            lokalerInventurSpeicher.initialisiereZaehlerlisten(zaehlerlisten);
            for (ZaehlerlisteDto zaehlerliste : zaehlerlisten) {
                lokalerInventurSpeicher.initialisiereArtikel(
                        zaehlerliste.getId(),
                        artikelNachZaehlerlistenId.get(zaehlerliste.getId())
                    );
            }
            if (apiModusAktiv) {
                lokalerInventurSpeicher.entferneVeralteteLeereZaehlerlisten(
                        new ArrayList<>(artikelNachZaehlerlistenId.keySet()));
            }
            merkeRemoteErfolg();
            remoteBestandSynchronisiert = !zaehlerlisten.isEmpty();
        } catch (RemoteDatenException exception) {
            merkeRemoteFehlschlag();
            letzteSynchronisierungsFehlermeldung = baueSynchronisierungsFehlermeldung(exception);
            // Ohne Remote-Seed bleibt nur der bereits vorhandene lokale Bestand nutzbar.
        }
    }

    private void synchronisiereAusstehendeAenderungen() {
        synchronisiereAusstehendeAenderungen(false);
    }

    private void synchronisiereAusstehendeAenderungen(boolean ignoriertPause) {
        if (istSqlSchreibenGesperrt()) {
            return;
        }
        if (!ignoriertPause && automatischeSynchronisierungPausiert) {
            return;
        }
        if (!darfRemoteVersuchtWerden()) {
            return;
        }

        for (Integer zaehlerlistenId : lokalerInventurSpeicher.ladeZaehlerlistenMitOffenerSynchronisierung()) {
            ZaehlmengenSpeicherAnfrage anfrage = baueSyncAnfrageAusLokalemBestand(zaehlerlistenId);
            if (anfrage == null) {
                continue;
            }

            try {
                ZaehlmengenSpeicherAntwort antwort = remoteDataSource.speichereZaehlmengen(anfrage);
                if (antwort.istErfolgreich()) {
                    Zaehlerliste zaehlerliste = lokalerInventurSpeicher.ladeZaehlerlisteFuerId(zaehlerlistenId);
                    if (zaehlerliste != null && zaehlerliste.istAbgeschlossen()) {
                        remoteDataSource.markiereZaehlerlisteAlsAbgeschlossen(zaehlerlistenId);
                    }
                    merkeRemoteErfolg();
                    lokalerInventurSpeicher.entferneZaehlerlisteAusSynchronisierung(zaehlerlistenId);
                    if (!lokalerInventurSpeicher.hatOffeneSynchronisierung()) {
                        letzteSynchronisierungsFehlermeldung = "";
                    }
                }
            } catch (RemoteDatenException exception) {
                merkeRemoteFehlschlag();
                letzteSynchronisierungsFehlermeldung = baueSynchronisierungsFehlermeldung(exception);
                break;
            }
        }
    }

    private boolean istSpeicherAnfrageGueltig(int zaehlerlistenId, List<Artikel> artikelListe) {
        return zaehlerlistenId > 0 && artikelListe != null && !artikelListe.isEmpty();
    }

    private ZaehlmengenSpeicherAntwort neueLeereSpeicherAntwort() {
        return new ZaehlmengenSpeicherAntwort(
                false,
                "PR: Speichern nicht moeglich. Die Liste enthaelt aktuell keine Artikel.",
                null
        );
    }

    private ZaehlmengenSpeicherAnfrage baueSyncAnfrageAusLokalemBestand(int zaehlerlistenId) {
        Zaehlerliste zaehlerliste = lokalerInventurSpeicher.ladeZaehlerlisteFuerId(zaehlerlistenId);
        if (zaehlerliste == null) {
            lokalerInventurSpeicher.entferneZaehlerlisteAusSynchronisierung(zaehlerlistenId);
            return null;
        }

        List<Artikel> artikelListe = lokalerInventurSpeicher.ladeArtikel(zaehlerlistenId);
        if (artikelListe == null || artikelListe.isEmpty()) {
            lokalerInventurSpeicher.entferneZaehlerlisteAusSynchronisierung(zaehlerlistenId);
            return null;
        }
        return ZaehlmengenSpeicherMapper.mappe(
                zaehlerlistenId,
                zaehlerliste.getGespeicherterZaehlerName(),
                zaehlerliste.getGespeicherterSchreiberName(),
                artikelListe
        );
    }

    private String baueSynchronisierungsFehlermeldung(RemoteDatenException exception) {
        String detail = sammleFehlerdetail(exception);
        String normalisiert = detail.toLowerCase(Locale.GERMANY);

        if (hatUrsache(exception, "UnknownHostException")) {
            return "Der SQL-Server-Host kann vom Tablet aus nicht aufgeloest werden. Bitte Hostname, DNS oder VPN pruefen.\n\nTechnisches Detail: " + detail;
        }
        if (hatUrsache(exception, "SocketTimeoutException") || hatUrsache(exception, "ConnectException")) {
            return "Der SQL-Server ist vom Tablet aus nicht erreichbar. Bitte WLAN, VPN, Firewall und SQL-Port pruefen.\n\nTechnisches Detail: " + detail;
        }
        if (normalisiert.contains("konfiguration ist unvollstaendig")) {
            return "SQL-Server-Konfiguration ist unvollstaendig. Host, Datenbank oder Zugangsdaten fehlen.";
        }
        if (normalisiert.contains("unknownhost")
                || normalisiert.contains("unknown server host")
                || normalisiert.contains("servername konnte nicht aufgel")) {
            return "Der SQL-Server-Host kann vom Tablet aus nicht aufgeloest werden. Bitte Hostname, DNS oder VPN pruefen.\n\nTechnisches Detail: " + detail;
        }
        if (normalisiert.contains("login failed")) {
            return "Die Anmeldung am SQL-Server ist fehlgeschlagen. Benutzername oder Passwort werden vom Server nicht akzeptiert.\n\nTechnisches Detail: " + detail;
        }
        if (normalisiert.contains("cannot open database")) {
            return "Die konfigurierte Datenbank kann nicht geoeffnet werden. Name oder Berechtigung auf dem SQL-Server pruefen.\n\nTechnisches Detail: " + detail;
        }
        if (normalisiert.contains("keine passende bestandszaehlungen-tabelle")) {
            return "Auf dem SQL-Server wurde keine passende Tabelle fuer Bestandszaehlungen gefunden.\n\nTechnisches Detail: " + detail;
        }
        if (normalisiert.contains("invalid object name")) {
            return "Mindestens eine erwartete SQL-Tabelle fehlt oder liegt in einem anderen Schema.\n\nTechnisches Detail: " + detail;
        }
        if (normalisiert.contains("failed to connect")
                || normalisiert.contains("connection refused")
                || normalisiert.contains("connect timed out")
                || normalisiert.contains("timed out")
                || normalisiert.contains("network is unreachable")
                || normalisiert.contains("sockettimeout")
                || normalisiert.contains("connection reset")) {
            return "Der SQL-Server ist vom Tablet aus nicht erreichbar. Bitte WLAN, VPN, Firewall und SQL-Port pruefen.\n\nTechnisches Detail: " + detail;
        }
        return detail;
    }

    private String sammleFehlerdetail(Throwable throwable) {
        String detail = "";
        Throwable aktuell = throwable;
        while (aktuell != null) {
            String meldung = aktuell.getMessage();
            if (meldung != null && !meldung.trim().isEmpty()) {
                detail = meldung.trim();
            }
            aktuell = aktuell.getCause();
        }
        return detail.isEmpty() ? "Unbekannter Fehler bei der Datenbank-Synchronisierung." : detail;
    }

    private boolean hatUrsache(Throwable throwable, String einfacherKlassenname) {
        Throwable aktuell = throwable;
        while (aktuell != null) {
            if (aktuell.getClass().getSimpleName().equals(einfacherKlassenname)) {
                return true;
            }
            aktuell = aktuell.getCause();
        }
        return false;
    }

    private boolean darfRemoteVersuchtWerden() {
        return System.currentTimeMillis() >= naechsterRemoteVersuchAbMillis;
    }

    private void merkeRemoteFehlschlag() {
        naechsterRemoteVersuchAbMillis = System.currentTimeMillis() + REMOTE_FEHLER_COOLDOWN_MS;
    }

    private void merkeRemoteErfolg() {
        naechsterRemoteVersuchAbMillis = 0L;
    }
}
