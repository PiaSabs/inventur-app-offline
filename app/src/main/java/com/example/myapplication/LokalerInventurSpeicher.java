package com.example.myapplication;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

// Diese lokale Datenbank ist die Offline-Basis der App.
// Morgen muss fuer API/Sync nur noch die Gegenstelle ergänzt werden.
public class LokalerInventurSpeicher extends SQLiteOpenHelper {

    private static final String DATENBANK_NAME = "inventur_offline.db";
    private static final int DATENBANK_VERSION = 11;

    private static final String TABELLE_ZAEHLERLISTE = "zaehlerliste";
    private static final String TABELLE_ARTIKEL = "artikel";
    private static final String TABELLE_BEARBEITERTABELLE = "bearbeitertabelle";
    private static final String TABELLE_INVENTUR_BEREICHE_VERTRETUNG = "inventur_bereiche_vertretung";
    private static final String TABELLE_INVENTUR_BREICHE = "inventur_breiche";
    private static final String TABELLE_TEILESTAMM = "teilestamm";
    private static final String TABELLE_BESTANDSZAEHLUNGEN = "bestandszaehlungen";
    private static final String TABELLE_ZAEHLERLISTEN_STATUS = "zaehlerlisten_status";
    private static final String TABELLE_SYNC_WARTESCHLANGE = "sync_warteschlange";

    public static final class InventurBereichInfo {
        private final int inventurBereichNr;
        private final String inventurBereichName;
        private final String zaehler;
        private final String schreiber;
        private final String sonstiges;
        private final int abBlattNr;

        public InventurBereichInfo(int inventurBereichNr, String inventurBereichName, String zaehler, String schreiber, String sonstiges, int abBlattNr) {
            this.inventurBereichNr = inventurBereichNr;
            this.inventurBereichName = inventurBereichName == null ? "" : inventurBereichName;
            this.zaehler = zaehler == null ? "" : zaehler;
            this.schreiber = schreiber == null ? "" : schreiber;
            this.sonstiges = sonstiges == null ? "" : sonstiges;
            this.abBlattNr = abBlattNr;
        }

        public int getInventurBereichNr() {
            return inventurBereichNr;
        }

        public String getInventurBereichName() {
            return inventurBereichName;
        }

        public String getZaehler() {
            return zaehler;
        }

        public String getSchreiber() {
            return schreiber;
        }

        public String getSonstiges() {
            return sonstiges;
        }

        public int getAbBlattNr() {
            return abBlattNr;
        }
    }

    public static final class BearbeiterInfo {
        private final String bearbeiterName;
        private final String vorname;
        private final String nachname;
        private final String telefonnummer;
        private final String firma;

        public BearbeiterInfo(String bearbeiterName, String vorname, String nachname, String telefonnummer, String firma) {
            this.bearbeiterName = bearbeiterName == null ? "" : bearbeiterName;
            this.vorname = vorname == null ? "" : vorname;
            this.nachname = nachname == null ? "" : nachname;
            this.telefonnummer = telefonnummer == null ? "" : telefonnummer;
            this.firma = firma == null ? "" : firma;
        }

        public String getAnzeigename() {
            String vollerName = (vorname + " " + nachname).trim();
            return vollerName.isEmpty() ? bearbeiterName : vollerName;
        }

        public String getTelefonnummer() {
            return telefonnummer;
        }

        public String getFirma() {
            return firma;
        }
    }

    public static final class BestandszaehlungSeed {
        private final int idSql;
        private final int positionsNummer;
        private final String identnummer;
        private final String lagerort;
        private final int istMenge;
        private final Integer gespeicherteZaehlmenge;
        private final Integer letzteZaehlmenge;
        private final int mindestbestand;
        private final String bezeichnung;

        public BestandszaehlungSeed(int idSql, int positionsNummer, String identnummer, String lagerort, int istMenge, int mindestbestand, String bezeichnung) {
            this(idSql, positionsNummer, identnummer, lagerort, istMenge, null, null, mindestbestand, bezeichnung);
        }

        public BestandszaehlungSeed(int idSql, int positionsNummer, String identnummer, String lagerort, int istMenge, Integer letzteZaehlmenge, int mindestbestand, String bezeichnung) {
            this(idSql, positionsNummer, identnummer, lagerort, istMenge, null, letzteZaehlmenge, mindestbestand, bezeichnung);
        }

        public BestandszaehlungSeed(int idSql, int positionsNummer, String identnummer, String lagerort, int istMenge, Integer gespeicherteZaehlmenge, Integer letzteZaehlmenge, int mindestbestand, String bezeichnung) {
            this.idSql = idSql;
            this.positionsNummer = positionsNummer;
            this.identnummer = identnummer == null ? "" : identnummer;
            this.lagerort = lagerort == null ? "0" : lagerort;
            this.istMenge = istMenge;
            this.gespeicherteZaehlmenge = gespeicherteZaehlmenge;
            this.letzteZaehlmenge = letzteZaehlmenge;
            this.mindestbestand = mindestbestand;
            this.bezeichnung = bezeichnung == null ? "" : bezeichnung;
        }

        public int getIdSql() {
            return idSql;
        }

        public int getPositionsNummer() {
            return positionsNummer;
        }

        public String getIdentnummer() {
            return identnummer;
        }

        public String getLagerort() {
            return lagerort;
        }

        public int getIstMenge() {
            return istMenge;
        }

        public Integer getGespeicherteZaehlmenge() {
            return gespeicherteZaehlmenge;
        }

        public Integer getLetzteZaehlmenge() {
            return letzteZaehlmenge;
        }

        public int getMindestbestand() {
            return mindestbestand;
        }

        public String getBezeichnung() {
            return bezeichnung;
        }
    }

    private static final class ZaehlerlistenStatusInfo {
        private final String zaehlerName;
        private final String schreiberName;
        private final boolean abgeschlossen;

        private ZaehlerlistenStatusInfo(String zaehlerName, String schreiberName, boolean abgeschlossen) {
            this.zaehlerName = zaehlerName == null ? "" : zaehlerName;
            this.schreiberName = schreiberName == null ? "" : schreiberName;
            this.abgeschlossen = abgeschlossen;
        }
    }

    public LokalerInventurSpeicher(Context context) {
        this(context, DATENBANK_NAME);
    }

    public LokalerInventurSpeicher(Context context, String datenbankName) {
        super(context, datenbankName, null, DATENBANK_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        erstelleZaehlerlisteTabelleFallsNoetig(db);
        erstelleArtikelTabelleFallsNoetig(db);
        erstelleBearbeitertabelleFallsNoetig(db);
        erstelleInventurBereicheVertretungTabelleFallsNoetig(db);
        erstelleInventurBreicheTabelleFallsNoetig(db);
        erstelleTeilestammTabelleFallsNoetig(db);
        erstelleBestandszaehlungenTabelleFallsNoetig(db);
        fuegeSpalteHinzuFallsFehlt(db, TABELLE_ARTIKEL, "letzte_zaehlmenge INTEGER");
        fuegeSpalteHinzuFallsFehlt(db, TABELLE_BESTANDSZAEHLUNGEN, "letzte_zaehlmenge REAL");
        erstelleZaehlerlistenStatusTabelleFallsNoetig(db);
        erstelleSyncWarteschlangenTabelleFallsNoetig(db);
        ergaenzeLokaleAenderungsinformationen(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            fuegeSpalteHinzuFallsFehlt(db, TABELLE_ARTIKEL, "pos_nr INTEGER NOT NULL DEFAULT 0");
            fuegeSpalteHinzuFallsFehlt(db, TABELLE_ARTIKEL, "lagerort TEXT NOT NULL DEFAULT ''");
        }

        if (oldVersion < 3) {
            erstelleBearbeitertabelleFallsNoetig(db);
            erstelleInventurBereicheVertretungTabelleFallsNoetig(db);
        }

        if (oldVersion < 4) {
            erstelleInventurBreicheTabelleFallsNoetig(db);
            erstelleTeilestammTabelleFallsNoetig(db);
            erstelleBestandszaehlungenTabelleFallsNoetig(db);
        }

        if (oldVersion < 5) {
            fuegeSpalteHinzuFallsFehlt(db, TABELLE_ZAEHLERLISTE, "zaehl_typ TEXT NOT NULL DEFAULT ''");
        }

        if (oldVersion < 6) {
            fuegeSpalteHinzuFallsFehlt(db, TABELLE_ZAEHLERLISTE, "stichtag TEXT NOT NULL DEFAULT ''");
            erstelleZaehlerlistenStatusTabelleFallsNoetig(db);
        }

        if (oldVersion < 7) {
            migriereArtikelTabelleAufPositionsschluessel(db);
            migriereBestandszaehlungenAufKompositschluessel(db);
        }

        if (oldVersion < 8) {
            fuegeSpalteHinzuFallsFehlt(db, TABELLE_ARTIKEL, "anmerkung TEXT NOT NULL DEFAULT ''");
        }

        if (oldVersion < 9) {
            erstelleSyncWarteschlangenTabelleFallsNoetig(db);
        }

        if (oldVersion < 10) {
            fuegeSpalteHinzuFallsFehlt(db, TABELLE_ARTIKEL, "letzte_zaehlmenge INTEGER");
            fuegeSpalteHinzuFallsFehlt(db, TABELLE_BESTANDSZAEHLUNGEN, "letzte_zaehlmenge REAL");
        }
        if (oldVersion < 11) {
            ergaenzeLokaleAenderungsinformationen(db);
        }
    }

    private void ergaenzeLokaleAenderungsinformationen(SQLiteDatabase db) {
        // Ohne nachgewiesenen Serverstand bleiben auch neue Datensaetze geschuetzt.
        for (String tabelle : new String[]{TABELLE_ARTIKEL, TABELLE_ZAEHLERLISTE}) {
            fuegeSpalteHinzuFallsFehlt(db, tabelle, "sync_unklar INTEGER NOT NULL DEFAULT 1");
            fuegeSpalteHinzuFallsFehlt(db, tabelle, "lokale_revision INTEGER NOT NULL DEFAULT 0");
            fuegeSpalteHinzuFallsFehlt(db, tabelle, "bestaetigter_serverstand TEXT");
        }
        // Die aktuelle Artikelquelle liefert noch keine nachgewiesene Zuordnung mit Serverstand.
        fuegeSpalteHinzuFallsFehlt(db, TABELLE_ARTIKEL, "sql_positions_id TEXT");
        fuegeSpalteHinzuFallsFehlt(db, TABELLE_ARTIKEL, "quell_blattnummer TEXT");
        fuegeSpalteHinzuFallsFehlt(db, TABELLE_ARTIKEL, "quell_stichtag TEXT");
        fuegeSpalteHinzuFallsFehlt(db, TABELLE_ARTIKEL, "menge_lokal_geaendert INTEGER NOT NULL DEFAULT 0");
        fuegeSpalteHinzuFallsFehlt(db, TABELLE_ARTIKEL, "notiz_lokal_geaendert INTEGER NOT NULL DEFAULT 0");
        fuegeSpalteHinzuFallsFehlt(db, TABELLE_ZAEHLERLISTE, "kopf_lokal_geaendert INTEGER NOT NULL DEFAULT 0");
        fuegeSpalteHinzuFallsFehlt(db, TABELLE_ZAEHLERLISTE, "abschluss_lokal_geaendert INTEGER NOT NULL DEFAULT 0");
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        erstelleZaehlerlisteTabelleFallsNoetig(db);
        erstelleArtikelTabelleFallsNoetig(db);
        erstelleBearbeitertabelleFallsNoetig(db);
        erstelleInventurBereicheVertretungTabelleFallsNoetig(db);
        erstelleInventurBreicheTabelleFallsNoetig(db);
        erstelleTeilestammTabelleFallsNoetig(db);
        erstelleBestandszaehlungenTabelleFallsNoetig(db);
        fuegeSpalteHinzuFallsFehlt(db, TABELLE_ARTIKEL, "letzte_zaehlmenge INTEGER");
        fuegeSpalteHinzuFallsFehlt(db, TABELLE_BESTANDSZAEHLUNGEN, "letzte_zaehlmenge REAL");
        erstelleZaehlerlistenStatusTabelleFallsNoetig(db);
        erstelleSyncWarteschlangenTabelleFallsNoetig(db);
    }

    public synchronized boolean hatZaehlerlisten() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABELLE_ZAEHLERLISTE, null);
        try {
            return cursor.moveToFirst() && cursor.getInt(0) > 0;
        } finally {
            cursor.close();
        }
    }

    public synchronized void initialisiereZaehlerlisten(List<ZaehlerlisteDto> zaehlerlisten) {
        if (zaehlerlisten == null || zaehlerlisten.isEmpty()) {
            return;
        }

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            boolean warLeer = zaehleDatensaetze(db, TABELLE_ZAEHLERLISTE) == 0;
            for (ZaehlerlisteDto zaehlerliste : zaehlerlisten) {
                try (Cursor vorhanden = db.rawQuery("SELECT 1 FROM zaehlerliste WHERE id = ?",
                        new String[]{String.valueOf(zaehlerliste.getId())})) {
                    if (vorhanden.moveToFirst()) continue;
                }
                ZaehlerlistenStatusInfo statusInfo = ladeZaehlerlistenStatusInfo(db, zaehlerliste.getId());
                ContentValues values = new ContentValues();
                values.put("id", zaehlerliste.getId());
                values.put("bezeichnung", zaehlerliste.getBezeichnung());
                values.put("blattnummer", zaehlerliste.getBlattnummer());
                values.put("bereich", zaehlerliste.getBereich());
                values.put("stichtag", zaehlerliste.getStichtag());
                values.put("letztes_zaehldatum", zaehlerliste.getLetztesZaehldatum());
                values.put("faellig", zaehlerliste.istFaellig() ? 1 : 0);
                values.put("abgeschlossen", statusInfo == null ? (zaehlerliste.istAbgeschlossen() ? 1 : 0) : (statusInfo.abgeschlossen ? 1 : 0));
                values.put("zaehler_name", statusInfo == null || statusInfo.zaehlerName.trim().isEmpty() ? zaehlerliste.getGespeicherterZaehlerName() : statusInfo.zaehlerName);
                values.put("schreiber_name", statusInfo == null || statusInfo.schreiberName.trim().isEmpty() ? zaehlerliste.getGespeicherterSchreiberName() : statusInfo.schreiberName);
                values.put("zaehl_typ", zaehlerliste.getZaehlTyp());
                db.insertOrThrow(TABELLE_ZAEHLERLISTE, null, values);
            }
            if (warLeer) wendeBeispielZaehlerlistenStatusFuerTestAn(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void initialisiereArtikel(int zaehlerlistenId, List<ArtikelDto> artikelListe) {
        if (artikelListe == null) {
            return;
        }

        java.util.Set<Long> quellIds = new java.util.HashSet<>();
        for (ArtikelDto artikel : artikelListe) {
            if (artikel.getSqlPositionsId() != null && (!quellIds.add(artikel.getSqlPositionsId())
                    || artikel.getQuellBlattnummer() != zaehlerlistenId)) {
                throw new IllegalArgumentException("SQL-Positionszuordnung ist nicht eindeutig.");
            }
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor kopf = db.rawQuery("SELECT abgeschlossen FROM zaehlerliste WHERE id = ?",
                    new String[]{String.valueOf(zaehlerlistenId)})) {
                if (kopf.moveToFirst()) {
                    if (kopf.getInt(0) != 0) return;
                } else if (!quellIds.isEmpty()) {
                    throw new IllegalArgumentException("Lokale Blattzuordnung fehlt.");
                }
            }
            for (ArtikelDto artikel : artikelListe) {
                // Der bisherige Import kann vorhandene Positionen noch nicht sicher abgleichen.
                try (Cursor vorhanden = db.rawQuery("SELECT 1 FROM artikel WHERE zaehlerlisten_id = ? AND pos_nr = ?",
                        new String[]{String.valueOf(zaehlerlistenId), String.valueOf(artikel.getPositionsNummer())})) {
                    if (vorhanden.moveToFirst()) continue;
                }
                if (artikel.getSqlPositionsId() != null) {
                    try (Cursor anderePosition = db.rawQuery("SELECT 1 FROM artikel WHERE sql_positions_id = ?",
                            new String[]{String.valueOf(artikel.getSqlPositionsId())})) {
                        if (anderePosition.moveToFirst()) continue;
                    }
                }
                ContentValues values = new ContentValues();
                if (artikel.getSqlPositionsId() != null) {
                    values.put("sql_positions_id", String.valueOf(artikel.getSqlPositionsId()));
                    values.put("quell_blattnummer", String.valueOf(artikel.getQuellBlattnummer()));
                }
                values.put("zaehlerlisten_id", zaehlerlistenId);
                values.put("pos_nr", artikel.getPositionsNummer());
                values.put("artikelnummer", artikel.getArtikelnummer());
                values.put("bezeichnung", artikel.getBezeichnung());
                values.put("lagerort", artikel.getLagerort());
                values.put("ist_menge", artikel.getIstMenge());
                if (artikel.getLetzteZaehlmenge() == null) {
                    values.putNull("letzte_zaehlmenge");
                } else {
                    values.put("letzte_zaehlmenge", artikel.getLetzteZaehlmenge());
                }
                values.put("mindestbestand", artikel.getMindestbestand());
                if (artikel.getZaehlMenge() == null) {
                    values.putNull("zaehl_menge");
                } else {
                    values.put("zaehl_menge", artikel.getZaehlMenge());
                }
                String vorhandeneAnmerkung = ladeArtikelAnmerkung(db, zaehlerlistenId, artikel.getPositionsNummer(), artikel.getArtikelnummer());
                String anmerkung = istLeer(artikel.getAnmerkung()) ? vorhandeneAnmerkung : artikel.getAnmerkung();
                values.put("anmerkung", anmerkung);
                db.insertOrThrow(TABELLE_ARTIKEL, null, values);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // Ausschliesslich vom lokalen Mock-Pfad aufgerufen. Vorhandene Zeilen nie ersetzen.
    public synchronized void ergaenzeDemoArtikel(int listenId, List<ArtikelDto> demoArtikel) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (ArtikelDto artikel : demoArtikel) {
                try (Cursor vorhanden = db.rawQuery("SELECT 1 FROM " + TABELLE_ARTIKEL
                        + " WHERE zaehlerlisten_id = ? AND artikelnummer = ?",
                        new String[]{String.valueOf(listenId), artikel.getArtikelnummer()})) {
                    if (vorhanden.moveToFirst()) {
                        continue;
                    }
                }
                int position = artikel.getPositionsNummer();
                try (Cursor belegt = db.rawQuery("SELECT 1 FROM " + TABELLE_ARTIKEL
                        + " WHERE zaehlerlisten_id = ? AND pos_nr = ?",
                        new String[]{String.valueOf(listenId), String.valueOf(position)})) {
                    if (belegt.moveToFirst()) {
                        try (Cursor maximum = db.rawQuery("SELECT MAX(pos_nr) FROM " + TABELLE_ARTIKEL
                                + " WHERE zaehlerlisten_id = ?", new String[]{String.valueOf(listenId)})) {
                            maximum.moveToFirst();
                            position = maximum.getInt(0) + 1;
                        }
                    }
                }
                ContentValues values = new ContentValues();
                values.put("zaehlerlisten_id", listenId);
                values.put("pos_nr", position);
                values.put("artikelnummer", artikel.getArtikelnummer());
                values.put("bezeichnung", artikel.getBezeichnung());
                values.put("lagerort", artikel.getLagerort());
                values.put("ist_menge", artikel.getIstMenge());
                values.put("mindestbestand", artikel.getMindestbestand());
                values.put("zaehl_menge", artikel.getZaehlMenge());
                values.put("letzte_zaehlmenge", artikel.getLetzteZaehlmenge());
                values.put("anmerkung", artikel.getAnmerkung());
                db.insertOrThrow(TABELLE_ARTIKEL, null, values);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized List<Zaehlerliste> ladeZaehlerlisten() {
        List<Zaehlerliste> zaehlerlisten = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(
                TABELLE_ZAEHLERLISTE,
                null,
                null,
                null,
                null,
                null,
                "id ASC"
        );

        try {
            while (cursor.moveToNext()) {
                zaehlerlisten.add(leseZaehlerliste(cursor));
            }
        } finally {
            cursor.close();
        }
        return zaehlerlisten;
    }

    public synchronized Zaehlerliste ladeZaehlerlisteFuerId(int zaehlerlistenId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(
                TABELLE_ZAEHLERLISTE,
                null,
                "id = ?",
                new String[]{String.valueOf(zaehlerlistenId)},
                null,
                null,
                null
        );

        try {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return leseZaehlerliste(cursor);
        } finally {
            cursor.close();
        }
    }

    public synchronized List<Artikel> ladeArtikel(int zaehlerlistenId) {
        List<Artikel> artikelListe = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(
                TABELLE_ARTIKEL,
                null,
                "zaehlerlisten_id = ?",
                new String[]{String.valueOf(zaehlerlistenId)},
                null,
                null,
                "pos_nr ASC"
        );

        try {
            while (cursor.moveToNext()) {
                artikelListe.add(new Artikel(
                        cursor.getInt(cursor.getColumnIndexOrThrow("pos_nr")),
                        cursor.getString(cursor.getColumnIndexOrThrow("artikelnummer")),
                        cursor.getString(cursor.getColumnIndexOrThrow("bezeichnung")),
                        cursor.getString(cursor.getColumnIndexOrThrow("lagerort")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("ist_menge")),
                        leseGanzzahlOderNull(cursor, "letzte_zaehlmenge", null),
                        cursor.getInt(cursor.getColumnIndexOrThrow("mindestbestand")),
                        cursor.isNull(cursor.getColumnIndexOrThrow("zaehl_menge"))
                                ? null
                                : cursor.getInt(cursor.getColumnIndexOrThrow("zaehl_menge")),
                        cursor.getString(cursor.getColumnIndexOrThrow("anmerkung")),
                        cursor.isNull(cursor.getColumnIndexOrThrow("sql_positions_id")) ? null
                                : cursor.getLong(cursor.getColumnIndexOrThrow("sql_positions_id")),
                        cursor.isNull(cursor.getColumnIndexOrThrow("quell_blattnummer")) ? null
                                : cursor.getInt(cursor.getColumnIndexOrThrow("quell_blattnummer"))
                ));
            }
        } finally {
            cursor.close();
        }
        return artikelListe;
    }

    public synchronized void speichereZaehlmengen(int zaehlerlistenId, String zaehlerName, String schreiberName, List<Artikel> artikelListe) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String zaehler = zaehlerName == null ? "" : zaehlerName;
            String schreiber = schreiberName == null ? "" : schreiberName;
            boolean abgeschlossen;
            try (Cursor kopf = db.rawQuery("SELECT abgeschlossen FROM zaehlerliste WHERE id = ?",
                    new String[]{String.valueOf(zaehlerlistenId)})) {
                if (!kopf.moveToFirst()) throw new IllegalArgumentException("Zaehlerliste fehlt lokal.");
                abgeschlossen = kopf.getInt(0) != 0;
            }
            db.execSQL("UPDATE zaehlerliste SET zaehler_name = ?, schreiber_name = ?, "
                            + "kopf_lokal_geaendert = 1, lokale_revision = lokale_revision + 1 "
                            + "WHERE id = ? AND (zaehler_name IS NOT ? OR schreiber_name IS NOT ?)",
                    new Object[]{zaehler, schreiber, zaehlerlistenId, zaehler, schreiber});
            speichereZaehlerlistenStatus(db, zaehlerlistenId, zaehler, schreiber, abgeschlossen);

            for (Artikel artikel : artikelListe) {
                String auswahl = artikel.getPositionsNummer() > 0
                        ? "zaehlerlisten_id = ? AND pos_nr = ? AND artikelnummer = ?"
                        : "zaehlerlisten_id = ? AND artikelnummer = ?";
                String[] parameter = artikel.getPositionsNummer() > 0
                        ? new String[]{String.valueOf(zaehlerlistenId), String.valueOf(artikel.getPositionsNummer()), artikel.getArtikelnummer()}
                        : new String[]{String.valueOf(zaehlerlistenId), artikel.getArtikelnummer()};
                try (Cursor position = db.query(TABELLE_ARTIKEL,
                        new String[]{"pos_nr", "zaehl_menge", "anmerkung", "sql_positions_id", "quell_blattnummer"},
                        auswahl, parameter, null, null, null)) {
                    if (position.getCount() != 1 || !position.moveToFirst()) {
                        throw new IllegalArgumentException("Artikelposition fehlt oder ist nicht eindeutig.");
                    }
                    if (artikel.getSqlPositionsId() != null && (position.isNull(3) || position.isNull(4)
                            || artikel.getSqlPositionsId() != position.getLong(3)
                            || artikel.getQuellBlattnummer() != position.getInt(4))) {
                        throw new IllegalArgumentException("SQL-Positionsidentitaet hat sich geaendert.");
                    }
                    Integer menge = position.isNull(1) ? null : position.getInt(1);
                    boolean mengeGeaendert = !java.util.Objects.equals(menge, artikel.getZaehlMenge());
                    boolean notizGeaendert = !java.util.Objects.equals(position.getString(2), artikel.getAnmerkung());
                    if (!mengeGeaendert && !notizGeaendert) continue;
                    // Die Revision steigt nur bei einer tatsaechlichen Aenderung.
                    db.execSQL("UPDATE artikel SET zaehl_menge = ?, anmerkung = ?, "
                                    + "menge_lokal_geaendert = MAX(menge_lokal_geaendert, ?), "
                                    + "notiz_lokal_geaendert = MAX(notiz_lokal_geaendert, ?), "
                                    + "lokale_revision = lokale_revision + 1 WHERE zaehlerlisten_id = ? AND pos_nr = ?",
                            new Object[]{artikel.getZaehlMenge(), artikel.getAnmerkung(), mengeGeaendert ? 1 : 0,
                                    notizGeaendert ? 1 : 0, zaehlerlistenId, position.getInt(0)});
                }
            }
            merkeZaehlerlisteFuerSynchronisierung(db, zaehlerlistenId);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void merkeZaehlerlisteFuerSynchronisierung(int zaehlerlistenId) {
        merkeZaehlerlisteFuerSynchronisierung(getWritableDatabase(), zaehlerlistenId);
    }

    private void merkeZaehlerlisteFuerSynchronisierung(SQLiteDatabase db, int zaehlerlistenId) {
        ContentValues values = new ContentValues();
        values.put("zaehlerlisten_id", zaehlerlistenId);
        values.put("geaendert_am", String.valueOf(System.currentTimeMillis()));
        if (db.insertWithOnConflict(TABELLE_SYNC_WARTESCHLANGE, null, values,
                SQLiteDatabase.CONFLICT_REPLACE) < 0) {
            throw new android.database.sqlite.SQLiteException("Uebertragungsauftrag konnte nicht gespeichert werden.");
        }
    }

    public synchronized void entferneZaehlerlisteAusSynchronisierung(int zaehlerlistenId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(
                TABELLE_SYNC_WARTESCHLANGE,
                "zaehlerlisten_id = ?",
                new String[]{String.valueOf(zaehlerlistenId)}
        );
    }

    public synchronized List<Integer> ladeZaehlerlistenMitOffenerSynchronisierung() {
        List<Integer> zaehlerlistenIds = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(
                TABELLE_SYNC_WARTESCHLANGE,
                new String[]{"zaehlerlisten_id"},
                null,
                null,
                null,
                null,
                "geaendert_am ASC"
        );

        try {
            while (cursor.moveToNext()) {
                zaehlerlistenIds.add(cursor.getInt(cursor.getColumnIndexOrThrow("zaehlerlisten_id")));
            }
        } finally {
            cursor.close();
        }

        return zaehlerlistenIds;
    }

    public synchronized boolean hatOffeneSynchronisierung() {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABELLE_SYNC_WARTESCHLANGE, null);
        try {
            return cursor.moveToFirst() && cursor.getInt(0) > 0;
        } finally {
            cursor.close();
        }
    }

    public synchronized boolean hatOffeneSynchronisierungFuerZaehlerliste(int zaehlerlistenId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM " + TABELLE_SYNC_WARTESCHLANGE + " WHERE zaehlerlisten_id = ?",
                new String[]{String.valueOf(zaehlerlistenId)}
        );
        try {
            return cursor.moveToFirst() && cursor.getInt(0) > 0;
        } finally {
            cursor.close();
        }
    }

    public synchronized boolean markiereZaehlerlisteAlsAbgeschlossen(int zaehlerlistenId) {
        return speichereAbschluss(zaehlerlistenId, true);
    }

    public synchronized boolean markiereZaehlerlisteAlsOffen(int zaehlerlistenId) {
        return speichereAbschluss(zaehlerlistenId, false);
    }

    private boolean speichereAbschluss(int zaehlerlistenId, boolean abgeschlossen) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor kopf = db.rawQuery("SELECT abgeschlossen FROM zaehlerliste WHERE id = ?",
                    new String[]{String.valueOf(zaehlerlistenId)})) {
                if (!kopf.moveToFirst()) return false;
                if ((kopf.getInt(0) != 0) != abgeschlossen) {
                    db.execSQL("UPDATE zaehlerliste SET abgeschlossen = ?, abschluss_lokal_geaendert = 1, "
                                    + "lokale_revision = lokale_revision + 1 WHERE id = ?",
                            new Object[]{abgeschlossen ? 1 : 0, zaehlerlistenId});
                }
            }
            speichereZaehlerlistenStatus(db, zaehlerlistenId, null, null, abgeschlossen);
            merkeZaehlerlisteFuerSynchronisierung(db, zaehlerlistenId);
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    public synchronized boolean sindAlleZaehlerlistenMitBezeichnungAbgeschlossen(String bezeichnung) {
        if (istLeer(bezeichnung)) {
            return false;
        }

        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT COUNT(*), SUM(CASE WHEN abgeschlossen = 1 THEN 1 ELSE 0 END) " +
                        "FROM " + TABELLE_ZAEHLERLISTE + " WHERE LOWER(TRIM(bezeichnung)) = ?",
                new String[]{bezeichnung.trim().toLowerCase()}
        );
        try {
            if (!cursor.moveToFirst()) {
                return false;
            }
            int anzahlBlaetter = cursor.getInt(0);
            int abgeschlosseneBlaetter = cursor.isNull(1) ? 0 : cursor.getInt(1);
            return anzahlBlaetter > 0 && anzahlBlaetter == abgeschlosseneBlaetter;
        } finally {
            cursor.close();
        }
    }

    // Ungeklaerte Listen bleiben auch ohne Artikel oder Warteschlangeneintrag erhalten.
    public synchronized void entferneVeralteteLeereZaehlerlisten(List<Integer> aktiveListenIds) {
        if (aktiveListenIds == null) {
            return;
        }
        StringBuilder where = new StringBuilder("NOT EXISTS (SELECT 1 FROM " + TABELLE_ARTIKEL
                + " a WHERE a.zaehlerlisten_id=" + TABELLE_ZAEHLERLISTE + ".id)"
                + " AND NOT EXISTS (SELECT 1 FROM " + TABELLE_SYNC_WARTESCHLANGE
                + " s WHERE s.zaehlerlisten_id=" + TABELLE_ZAEHLERLISTE + ".id)");
        where.append(" AND sync_unklar = 0 AND bestaetigter_serverstand IS NOT NULL"
                + " AND kopf_lokal_geaendert = 0 AND abschluss_lokal_geaendert = 0");
        List<String> argumente = new ArrayList<>();
        if (!aktiveListenIds.isEmpty()) {
            where.append(" AND id NOT IN (");
            for (int i = 0; i < aktiveListenIds.size(); i++) {
                if (i > 0) where.append(",");
                where.append("?");
                argumente.add(String.valueOf(aktiveListenIds.get(i)));
            }
            where.append(")");
        }
        getWritableDatabase().delete(TABELLE_ZAEHLERLISTE, where.toString(),
                argumente.toArray(new String[0]));
    }

    public synchronized void loescheAlleDaten() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(TABELLE_ARTIKEL, null, null);
            db.delete(TABELLE_ZAEHLERLISTE, null, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void initialisiereBeispieldatenFuerHilfstabellenFallsLeer() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (zaehleDatensaetze(db, TABELLE_BEARBEITERTABELLE) == 0) {
                for (int index = 1; index <= 20; index++) {
                    ContentValues values = new ContentValues();
                    String bearbeiterNr = String.format("B%03d", index);
                    values.put("bearbeiter_nr", bearbeiterNr);
                    values.put("bearbeiter_name", "Bearbeiter " + index);
                    values.put("tel_bearbeiter", "");
                    values.put("nt_username", "demo_user_" + index);
                    values.put("sql_username", "");
                    values.put("firma", "DEMO");
                    values.put("nachname", "Nachname " + index);
                    values.put("vorname", "Vorname " + index);
                    values.put("std_email", "bearbeiter" + index + "@example.invalid");
                    db.insertWithOnConflict(TABELLE_BEARBEITERTABELLE, null, values, SQLiteDatabase.CONFLICT_IGNORE);
                }
            }

            if (zaehleDatensaetze(db, TABELLE_INVENTUR_BEREICHE_VERTRETUNG) == 0) {
                for (int index = 1; index <= 20; index++) {
                    ContentValues values = new ContentValues();
                    values.put("id_sql", 2000 + index);
                    values.put("inventur_bereich_nr", index);
                    values.put("datum", "2026-06-30");
                    values.put("vertretung_zaehler", "Vertretung Z " + index);
                    values.put("vertretung_schreiber", "Vertretung S " + index);
                    values.put("bearbeiter_nr", String.format("B%03d", index));
                    db.insertWithOnConflict(TABELLE_INVENTUR_BEREICHE_VERTRETUNG, null, values, SQLiteDatabase.CONFLICT_IGNORE);
                }
            }

            if (zaehleDatensaetze(db, TABELLE_INVENTUR_BREICHE) == 0) {
                for (int index = 1; index <= 20; index++) {
                    ContentValues values = new ContentValues();
                    values.put("inv_bereich_nr", index);
                    values.put("inv_bereich_name", "Inventurbereich " + index);
                    values.put("zaehler", "Demo-Zaehler");
                    values.put("schreiber", "Demo-Schreiber");
                    values.put("sonstiges", "Hinweis " + index);
                    values.put("ag_durchlauf_nr", 100 + index);
                    values.put("ab_blatt_nr", 9000 + index);
                    values.put("sort_nr", index);
                    values.put("inv_bereich_twist", index % 5);
                    values.put("firma", "DEMO");
                    db.insertWithOnConflict(TABELLE_INVENTUR_BREICHE, null, values, SQLiteDatabase.CONFLICT_IGNORE);
                }
            }

            if (zaehleDatensaetze(db, TABELLE_TEILESTAMM) == 0) {
                for (int index = 1; index <= 20; index++) {
                    ContentValues values = new ContentValues();
                    String identnummer = String.format("T%07d", index);
                    values.put("identnummer", identnummer);
                    values.put("bezeichnung1", "Testartikel " + index);
                    values.put("bezeichnung2", "Serienteil " + index);
                    values.put("mengeneinheit", "St");
                    values.put("mengeneinheit_lager", "St");
                    values.put("mindestbestand", 5 + index);
                    values.put("lager_ort", "R" + ((index - 1) % 9 + 1));
                    values.put("lager_standorte", "R" + ((index - 1) % 9 + 1));
                    db.insertWithOnConflict(TABELLE_TEILESTAMM, null, values, SQLiteDatabase.CONFLICT_IGNORE);
                }
            }

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void angleicheBeispielWochenlistenZuordnungFuerTest(String zaehlerName, String schreiberName) {
        if (istLeer(zaehlerName) || istLeer(schreiberName)) {
            return;
        }

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int blattNr = 9001; blattNr <= 9020; blattNr++) {
                ZaehlerlistenStatusInfo statusInfo = ladeZaehlerlistenStatusInfo(db, blattNr);
                if (statusInfo != null && !istPlatzhalterName(statusInfo.zaehlerName, "zaehler") && !statusInfo.zaehlerName.trim().isEmpty()
                        && !istPlatzhalterName(statusInfo.schreiberName, "schreiber") && !statusInfo.schreiberName.trim().isEmpty()) {
                    continue;
                }

                speichereZaehlerlistenStatus(db, blattNr, zaehlerName, schreiberName, null);

                ContentValues values = new ContentValues();
                values.put("zaehler_name", zaehlerName);
                values.put("schreiber_name", schreiberName);
                db.update(
                        TABELLE_ZAEHLERLISTE,
                        values,
                        "id = ? AND (zaehler_name = '' OR schreiber_name = '' OR zaehler_name LIKE 'Zaehler %' OR schreiber_name LIKE 'Schreiber %')",
                        new String[]{String.valueOf(blattNr)}
                );
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized InventurBereichInfo ladeInventurBereichFuerNummer(int inventurBereichNr) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(
                TABELLE_INVENTUR_BREICHE,
                new String[]{"inv_bereich_nr", "inv_bereich_name", "zaehler", "schreiber", "sonstiges", "ab_blatt_nr"},
                "inv_bereich_nr = ?",
                new String[]{String.valueOf(inventurBereichNr)},
                null,
                null,
                null
        );

        try {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return new InventurBereichInfo(
                    cursor.getInt(cursor.getColumnIndexOrThrow("inv_bereich_nr")),
                    cursor.getString(cursor.getColumnIndexOrThrow("inv_bereich_name")),
                    cursor.getString(cursor.getColumnIndexOrThrow("zaehler")),
                    cursor.getString(cursor.getColumnIndexOrThrow("schreiber")),
                    cursor.getString(cursor.getColumnIndexOrThrow("sonstiges")),
                    cursor.getInt(cursor.getColumnIndexOrThrow("ab_blatt_nr"))
            );
        } finally {
            cursor.close();
        }
    }

    public synchronized List<InventurBereichInfo> ladeAlleInventurBereiche() {
        List<InventurBereichInfo> inventurBereiche = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(
                TABELLE_INVENTUR_BREICHE,
                new String[]{"inv_bereich_nr", "inv_bereich_name", "zaehler", "schreiber", "sonstiges", "ab_blatt_nr"},
                null,
                null,
                null,
                null,
                "ab_blatt_nr ASC"
        );

        try {
            while (cursor.moveToNext()) {
                inventurBereiche.add(new InventurBereichInfo(
                        cursor.getInt(cursor.getColumnIndexOrThrow("inv_bereich_nr")),
                        cursor.getString(cursor.getColumnIndexOrThrow("inv_bereich_name")),
                        cursor.getString(cursor.getColumnIndexOrThrow("zaehler")),
                        cursor.getString(cursor.getColumnIndexOrThrow("schreiber")),
                        cursor.getString(cursor.getColumnIndexOrThrow("sonstiges")),
                        cursor.getInt(cursor.getColumnIndexOrThrow("ab_blatt_nr"))
                ));
            }
        } finally {
            cursor.close();
        }

        return inventurBereiche;
    }

    public synchronized List<BearbeiterInfo> ladeBearbeiterFuerFirmenMitTelefon(List<String> firmenFilter) {
        List<BearbeiterInfo> bearbeiter = new ArrayList<>();
        if (firmenFilter == null || firmenFilter.isEmpty()) {
            return bearbeiter;
        }

        List<String> bereinigteFilter = new ArrayList<>();
        for (String firma : firmenFilter) {
            if (firma == null) {
                continue;
            }
            String bereinigt = firma.trim().toLowerCase();
            if (!bereinigt.isEmpty()) {
                bereinigteFilter.add(bereinigt);
            }
        }
        if (bereinigteFilter.isEmpty()) {
            return bearbeiter;
        }

        StringBuilder platzhalter = new StringBuilder();
        String[] argumente = new String[bereinigteFilter.size()];
        for (int index = 0; index < bereinigteFilter.size(); index++) {
            if (index > 0) {
                platzhalter.append(", ");
            }
            platzhalter.append("?");
            argumente[index] = bereinigteFilter.get(index);
        }

        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT bearbeiter_name, vorname, nachname, tel_bearbeiter, firma " +
                        "FROM " + TABELLE_BEARBEITERTABELLE + " " +
                        "WHERE TRIM(COALESCE(tel_bearbeiter, '')) <> '' " +
                        "AND LOWER(TRIM(COALESCE(firma, ''))) IN (" + platzhalter + ") " +
                        "ORDER BY nachname ASC, vorname ASC, bearbeiter_name ASC",
                argumente
        );

        try {
            while (cursor.moveToNext()) {
                bearbeiter.add(new BearbeiterInfo(
                        cursor.getString(cursor.getColumnIndexOrThrow("bearbeiter_name")),
                        cursor.getString(cursor.getColumnIndexOrThrow("vorname")),
                        cursor.getString(cursor.getColumnIndexOrThrow("nachname")),
                        cursor.getString(cursor.getColumnIndexOrThrow("tel_bearbeiter")),
                        cursor.getString(cursor.getColumnIndexOrThrow("firma"))
                ));
            }
        } finally {
            cursor.close();
        }

        return bearbeiter;
    }

    public synchronized String ladeAktuellenStichtagFuerBlatt(int blattNr, String datumGrenze) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT MAX(datum) FROM " + TABELLE_BESTANDSZAEHLUNGEN + " WHERE blatt_nr = ? AND datum > ?",
                new String[]{String.valueOf(blattNr), datumGrenze}
        );

        try {
            if (!cursor.moveToFirst() || cursor.isNull(0)) {
                return null;
            }
            return cursor.getString(0);
        } finally {
            cursor.close();
        }
    }

    public synchronized boolean hatBestandszaehlungenFuerBlattUndDatum(int blattNr, String datum) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM " + TABELLE_BESTANDSZAEHLUNGEN + " WHERE blatt_nr = ? AND datum = ?",
                new String[]{String.valueOf(blattNr), datum}
        );

        try {
            return cursor.moveToFirst() && cursor.getInt(0) > 0;
        } finally {
            cursor.close();
        }
    }

    public synchronized void initialisiereBestandszaehlungenFuerBlattFallsLeer(int blattNr, String datum) {
        initialisiereBestandszaehlungenFuerBlattFallsLeer(blattNr, datum, null);
    }

    public synchronized void initialisiereBestandszaehlungenFuerBlattFallsLeer(int blattNr, String datum, List<BestandszaehlungSeed> seeds) {
        if (hatBestandszaehlungenFuerBlattUndDatum(blattNr, datum)) {
            return;
        }

        List<BestandszaehlungSeed> verwendeteSeeds = seeds;
        if (verwendeteSeeds == null || verwendeteSeeds.isEmpty()) {
            verwendeteSeeds = erstelleBeispielSeedsFuerTest(blattNr);
        }
        if (verwendeteSeeds == null || verwendeteSeeds.isEmpty()) {
            verwendeteSeeds = erstelleStandardSeedsFuerBlatt(blattNr);
        }

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (BestandszaehlungSeed seed : verwendeteSeeds) {
                sichereTeilestammBasisFallsFehlt(db, seed);
                ContentValues values = new ContentValues();
                values.put("id_sql", seed.getIdSql());
                values.put("ident_nr_bestand", seed.getIdentnummer());
                values.put("datum", datum);
                values.put("lagerort", seed.getLagerort());
                values.put("menge", seed.getIstMenge());
                if (seed.getLetzteZaehlmenge() == null) {
                    values.putNull("letzte_zaehlmenge");
                } else {
                    values.put("letzte_zaehlmenge", seed.getLetzteZaehlmenge());
                }
                values.put("blatt_nr", blattNr);
                values.put("pos_nr", seed.getPositionsNummer());
                if (seed.getGespeicherteZaehlmenge() == null) {
                    values.putNull("menge_lager");
                } else {
                    values.put("menge_lager", seed.getGespeicherteZaehlmenge());
                }
                values.put("zaehl_nr", seed.getPositionsNummer());
                values.put("hinweis", 0);
                values.put("gesamt_menge", seed.getIstMenge());
                values.put("menge_offen", 0);
                values.put("bestell_menge", 0);
                values.put("bedarf", 0);
                values.put("geprueft_kz", "N");
                values.put("dat_inventur", datum);
                db.insertWithOnConflict(TABELLE_BESTANDSZAEHLUNGEN, null, values, SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized List<ArtikelDto> ladeArtikelAusBestandszaehlungen(int blattNr, String datum) {
        List<ArtikelDto> artikelListe = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT b.pos_nr, b.ident_nr_bestand, b.lagerort, b.menge, b.letzte_zaehlmenge, b.menge_lager, " +
                        "t.bezeichnung1, t.bezeichnung2, t.mindestbestand " +
                        "FROM " + TABELLE_BESTANDSZAEHLUNGEN + " b " +
                        "LEFT JOIN " + TABELLE_TEILESTAMM + " t ON t.identnummer = b.ident_nr_bestand " +
                        "WHERE b.blatt_nr = ? AND b.datum = ? " +
                        "ORDER BY b.pos_nr ASC",
                new String[]{String.valueOf(blattNr), datum}
        );

        try {
            while (cursor.moveToNext()) {
                String bezeichnung1 = cursor.getString(cursor.getColumnIndexOrThrow("bezeichnung1"));
                String bezeichnung2 = cursor.getString(cursor.getColumnIndexOrThrow("bezeichnung2"));
                String bezeichnung = kombiniereBezeichnung(bezeichnung1, bezeichnung2, cursor.getString(cursor.getColumnIndexOrThrow("ident_nr_bestand")));
                Integer zaehlMenge = cursor.isNull(cursor.getColumnIndexOrThrow("menge_lager"))
                        ? null
                        : Math.round(cursor.getFloat(cursor.getColumnIndexOrThrow("menge_lager")));

                artikelListe.add(new ArtikelDto(
                        cursor.getInt(cursor.getColumnIndexOrThrow("pos_nr")),
                        cursor.getString(cursor.getColumnIndexOrThrow("ident_nr_bestand")),
                        bezeichnung,
                        cursor.getString(cursor.getColumnIndexOrThrow("lagerort")),
                        Math.round(cursor.getFloat(cursor.getColumnIndexOrThrow("menge"))),
                        leseGanzzahlOderNull(cursor, "letzte_zaehlmenge", null),
                        leseGanzzahlOderNull(cursor, "mindestbestand", 0),
                        zaehlMenge,
                        ""
                ));
            }
        } finally {
            cursor.close();
        }
        return artikelListe;
    }

    public synchronized void aktualisiereBestandszaehlmengen(int blattNr, String datum, List<Artikel> artikelListe) {
        if (artikelListe == null || artikelListe.isEmpty()) {
            return;
        }

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Artikel artikel : artikelListe) {
                ContentValues values = new ContentValues();
                if (artikel.getZaehlMenge() == null) {
                    values.putNull("menge_lager");
                } else {
                    values.put("menge_lager", artikel.getZaehlMenge());
                }
                db.update(
                        TABELLE_BESTANDSZAEHLUNGEN,
                        values,
                        artikel.getPositionsNummer() > 0
                                ? "blatt_nr = ? AND datum = ? AND pos_nr = ?"
                                : "blatt_nr = ? AND datum = ? AND ident_nr_bestand = ?",
                        artikel.getPositionsNummer() > 0
                                ? new String[]{String.valueOf(blattNr), datum, String.valueOf(artikel.getPositionsNummer())}
                                : new String[]{String.valueOf(blattNr), datum, artikel.getArtikelnummer()}
                );
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private Zaehlerliste leseZaehlerliste(Cursor cursor) {
        return new Zaehlerliste(
                cursor.getInt(cursor.getColumnIndexOrThrow("id")),
                cursor.getString(cursor.getColumnIndexOrThrow("bezeichnung")),
                cursor.getString(cursor.getColumnIndexOrThrow("blattnummer")),
                cursor.getString(cursor.getColumnIndexOrThrow("bereich")),
                cursor.getString(cursor.getColumnIndexOrThrow("stichtag")),
                cursor.getString(cursor.getColumnIndexOrThrow("letztes_zaehldatum")),
                cursor.getInt(cursor.getColumnIndexOrThrow("faellig")) == 1,
                cursor.getInt(cursor.getColumnIndexOrThrow("abgeschlossen")) == 1,
                cursor.getString(cursor.getColumnIndexOrThrow("zaehler_name")),
                cursor.getString(cursor.getColumnIndexOrThrow("schreiber_name")),
                cursor.getString(cursor.getColumnIndexOrThrow("zaehl_typ"))
        );
    }

    private void erstelleZaehlerlisteTabelleFallsNoetig(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS " + TABELLE_ZAEHLERLISTE + " (" +
                        "id INTEGER PRIMARY KEY, " +
                        "bezeichnung TEXT NOT NULL, " +
                        "blattnummer TEXT NOT NULL, " +
                        "bereich TEXT NOT NULL, " +
                        "stichtag TEXT NOT NULL DEFAULT '', " +
                        "letztes_zaehldatum TEXT NOT NULL, " +
                        "faellig INTEGER NOT NULL, " +
                        "abgeschlossen INTEGER NOT NULL DEFAULT 0, " +
                        "zaehler_name TEXT NOT NULL DEFAULT '', " +
                        "schreiber_name TEXT NOT NULL DEFAULT '', " +
                        "zaehl_typ TEXT NOT NULL DEFAULT ''" +
                        ")"
        );
    }

    private void erstelleArtikelTabelleFallsNoetig(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS " + TABELLE_ARTIKEL + " (" +
                        "zaehlerlisten_id INTEGER NOT NULL, " +
                        "pos_nr INTEGER NOT NULL DEFAULT 0, " +
                        "artikelnummer TEXT NOT NULL, " +
                        "bezeichnung TEXT NOT NULL, " +
                        "lagerort TEXT NOT NULL DEFAULT '', " +
                        "ist_menge INTEGER NOT NULL, " +
                        "letzte_zaehlmenge INTEGER, " +
                        "mindestbestand INTEGER NOT NULL, " +
                        "zaehl_menge INTEGER, " +
                        "anmerkung TEXT NOT NULL DEFAULT '', " +
                        "PRIMARY KEY(zaehlerlisten_id, pos_nr)" +
                        ")"
        );
    }

    private void erstelleBearbeitertabelleFallsNoetig(SQLiteDatabase db) {
        // Minimales lokales Demo-Schema; keine Kopie des frueheren Unternehmensschemas.
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABELLE_BEARBEITERTABELLE + " (bearbeiter_nr TEXT PRIMARY KEY, bearbeiter_name TEXT, tel_bearbeiter TEXT, nt_username TEXT, sql_username TEXT, firma TEXT, nachname TEXT, vorname TEXT, std_email TEXT DEFAULT 'demo@example.invalid')");
    }

    private void erstelleInventurBereicheVertretungTabelleFallsNoetig(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS " + TABELLE_INVENTUR_BEREICHE_VERTRETUNG + " (" +
                        "id_sql INTEGER PRIMARY KEY, " +
                        "inventur_bereich_nr INTEGER, " +
                        "datum TEXT, " +
                        "vertretung_zaehler TEXT, " +
                        "vertretung_schreiber TEXT, " +
                        "bearbeiter_nr TEXT" +
                        ")"
        );
    }

    private void erstelleInventurBreicheTabelleFallsNoetig(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS " + TABELLE_INVENTUR_BREICHE + " (" +
                        "inv_bereich_nr INTEGER PRIMARY KEY, " +
                        "inv_bereich_name TEXT NOT NULL, " +
                        "zaehler TEXT, " +
                        "schreiber TEXT, " +
                        "sonstiges TEXT, " +
                        "ag_durchlauf_nr INTEGER, " +
                        "ab_blatt_nr INTEGER, " +
                        "sort_nr INTEGER, " +
                        "inv_bereich_twist INTEGER, " +
                        "firma TEXT" +
                        ")"
        );
    }

    private void erstelleTeilestammTabelleFallsNoetig(SQLiteDatabase db) {
        // Minimales lokales Demo-Schema; keine Kopie des frueheren Unternehmensschemas.
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABELLE_TEILESTAMM + " (identnummer TEXT PRIMARY KEY, bezeichnung1 TEXT, bezeichnung2 TEXT, mengeneinheit TEXT, mengeneinheit_lager TEXT, lager_ort TEXT, mindestbestand REAL, ag_durchlauf_nr INTEGER, lager_standorte TEXT)");
    }

    private void erstelleBestandszaehlungenTabelleFallsNoetig(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS " + TABELLE_BESTANDSZAEHLUNGEN + " (" +
                        "id_sql INTEGER, " +
                        "ident_nr_bestand TEXT NOT NULL, " +
                        "datum TEXT NOT NULL, " +
                        "lagerort TEXT NOT NULL, " +
                        "menge REAL, " +
                        "letzte_zaehlmenge REAL, " +
                        "blatt_nr INTEGER, " +
                        "pos_nr INTEGER, " +
                        "menge_lager REAL, " +
                        "zaehl_nr INTEGER, " +
                        "hinweis INTEGER, " +
                        "gesamt_menge REAL, " +
                        "menge_offen REAL, " +
                        "bestell_menge REAL, " +
                        "bedarf REAL, " +
                        "geprueft_kz TEXT, " +
                        "dat_inventur TEXT, " +
                        "PRIMARY KEY(blatt_nr, pos_nr, datum)" +
                        ")"
        );
    }

    private void erstelleZaehlerlistenStatusTabelleFallsNoetig(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS " + TABELLE_ZAEHLERLISTEN_STATUS + " (" +
                        "zaehlerlisten_id INTEGER PRIMARY KEY, " +
                        "zaehler_name TEXT NOT NULL DEFAULT '', " +
                        "schreiber_name TEXT NOT NULL DEFAULT '', " +
                        "abgeschlossen INTEGER NOT NULL DEFAULT 0" +
                        ")"
        );
    }

    private void erstelleSyncWarteschlangenTabelleFallsNoetig(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS " + TABELLE_SYNC_WARTESCHLANGE + " (" +
                        "zaehlerlisten_id INTEGER PRIMARY KEY, " +
                        "geaendert_am TEXT NOT NULL DEFAULT ''" +
                        ")"
        );
    }

    private void migriereArtikelTabelleAufPositionsschluessel(SQLiteDatabase db) {
        if (!existiertTabelle(db, TABELLE_ARTIKEL)) {
            erstelleArtikelTabelleFallsNoetig(db);
            return;
        }

        db.execSQL("ALTER TABLE " + TABELLE_ARTIKEL + " RENAME TO " + TABELLE_ARTIKEL + "_alt_v7");
        erstelleArtikelTabelleFallsNoetig(db);
        db.execSQL(
                "INSERT OR REPLACE INTO " + TABELLE_ARTIKEL + " (" +
                        "zaehlerlisten_id, pos_nr, artikelnummer, bezeichnung, lagerort, ist_menge, mindestbestand, zaehl_menge" +
                        ") SELECT " +
                        "zaehlerlisten_id, pos_nr, artikelnummer, bezeichnung, lagerort, ist_menge, mindestbestand, zaehl_menge " +
                        "FROM " + TABELLE_ARTIKEL + "_alt_v7"
        );
        db.execSQL("DROP TABLE " + TABELLE_ARTIKEL + "_alt_v7");
    }

    private void migriereBestandszaehlungenAufKompositschluessel(SQLiteDatabase db) {
        if (!existiertTabelle(db, TABELLE_BESTANDSZAEHLUNGEN)) {
            erstelleBestandszaehlungenTabelleFallsNoetig(db);
            return;
        }

        db.execSQL("ALTER TABLE " + TABELLE_BESTANDSZAEHLUNGEN + " RENAME TO " + TABELLE_BESTANDSZAEHLUNGEN + "_alt_v7");
        erstelleBestandszaehlungenTabelleFallsNoetig(db);
        db.execSQL(
                "INSERT OR REPLACE INTO " + TABELLE_BESTANDSZAEHLUNGEN + " (" +
                        "id_sql, ident_nr_bestand, datum, lagerort, menge, blatt_nr, pos_nr, menge_lager, zaehl_nr, hinweis, gesamt_menge, menge_offen, bestell_menge, bedarf, geprueft_kz, dat_inventur" +
                        ") SELECT " +
                        "id_sql, ident_nr_bestand, datum, lagerort, menge, blatt_nr, pos_nr, menge_lager, zaehl_nr, hinweis, gesamt_menge, menge_offen, bestell_menge, bedarf, geprueft_kz, dat_inventur " +
                        "FROM " + TABELLE_BESTANDSZAEHLUNGEN + "_alt_v7"
        );
        db.execSQL("DROP TABLE " + TABELLE_BESTANDSZAEHLUNGEN + "_alt_v7");
    }

    private void speichereZaehlerlistenStatus(SQLiteDatabase db, int zaehlerlistenId, String zaehlerName, String schreiberName, Boolean abgeschlossen) {
        ZaehlerlistenStatusInfo vorhandenerStatus = ladeZaehlerlistenStatusInfo(db, zaehlerlistenId);
        ContentValues values = new ContentValues();
        values.put("zaehlerlisten_id", zaehlerlistenId);
        values.put("zaehler_name", zaehlerName == null ? (vorhandenerStatus == null ? "" : vorhandenerStatus.zaehlerName) : zaehlerName);
        values.put("schreiber_name", schreiberName == null ? (vorhandenerStatus == null ? "" : vorhandenerStatus.schreiberName) : schreiberName);
        values.put("abgeschlossen", abgeschlossen == null
                ? (vorhandenerStatus != null && vorhandenerStatus.abgeschlossen ? 1 : 0)
                : (abgeschlossen ? 1 : 0));
        if (db.insertWithOnConflict(TABELLE_ZAEHLERLISTEN_STATUS, null, values,
                SQLiteDatabase.CONFLICT_REPLACE) < 0) {
            throw new android.database.sqlite.SQLiteException("Lokaler Listenstatus konnte nicht gespeichert werden.");
        }
    }

    private ZaehlerlistenStatusInfo ladeZaehlerlistenStatusInfo(SQLiteDatabase db, int zaehlerlistenId) {
        Cursor cursor = db.query(
                TABELLE_ZAEHLERLISTEN_STATUS,
                new String[]{"zaehler_name", "schreiber_name", "abgeschlossen"},
                "zaehlerlisten_id = ?",
                new String[]{String.valueOf(zaehlerlistenId)},
                null,
                null,
                null
        );
        try {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return new ZaehlerlistenStatusInfo(
                    cursor.getString(cursor.getColumnIndexOrThrow("zaehler_name")),
                    cursor.getString(cursor.getColumnIndexOrThrow("schreiber_name")),
                    cursor.getInt(cursor.getColumnIndexOrThrow("abgeschlossen")) == 1
            );
        } finally {
            cursor.close();
        }
    }

    private String ladeArtikelAnmerkung(SQLiteDatabase db, int zaehlerlistenId, int positionsNummer, String artikelnummer) {
        Cursor cursor = db.query(
                TABELLE_ARTIKEL,
                new String[]{"anmerkung"},
                positionsNummer > 0
                        ? "zaehlerlisten_id = ? AND pos_nr = ?"
                        : "zaehlerlisten_id = ? AND artikelnummer = ?",
                positionsNummer > 0
                        ? new String[]{String.valueOf(zaehlerlistenId), String.valueOf(positionsNummer)}
                        : new String[]{String.valueOf(zaehlerlistenId), artikelnummer},
                null,
                null,
                null
        );
        try {
            if (!cursor.moveToFirst()) {
                return "";
            }
            return cursor.getString(cursor.getColumnIndexOrThrow("anmerkung"));
        } finally {
            cursor.close();
        }
    }

    private void wendeBeispielZaehlerlistenStatusFuerTestAn(SQLiteDatabase db) {
        aktualisiereBeispielZaehlerlistenBezeichnung(db, 9001, 9003, "Zaehlerliste 9001");
        aktualisiereBeispielZaehlerlistenBezeichnung(db, 9004, 9006, "Zaehlerliste 9004");
        aktualisiereBeispielZaehlerlistenBezeichnung(db, 9010, 9015, "Zaehlerliste 9010");
        setzeBeispielBlattStatusWennNochNichtVorhanden(db, 9001, false);
        setzeBeispielBlattStatusWennNochNichtVorhanden(db, 9002, true);
        setzeBeispielBlattStatusWennNochNichtVorhanden(db, 9003, false);
        setzeBeispielBlattStatusWennNochNichtVorhanden(db, 9004, true);
        setzeBeispielBlattStatusWennNochNichtVorhanden(db, 9005, true);
        setzeBeispielBlattStatusWennNochNichtVorhanden(db, 9006, true);
        setzeBeispielBlattStatusWennNochNichtVorhanden(db, 9010, false);
        setzeBeispielBlattStatusWennNochNichtVorhanden(db, 9011, false);
        setzeBeispielBlattStatusWennNochNichtVorhanden(db, 9012, true);
        setzeBeispielBlattStatusWennNochNichtVorhanden(db, 9013, false);
        setzeBeispielBlattStatusWennNochNichtVorhanden(db, 9014, true);
        setzeBeispielBlattStatusWennNochNichtVorhanden(db, 9015, false);
    }

    private void aktualisiereBeispielZaehlerlistenBezeichnung(SQLiteDatabase db, int vonBlattNr, int bisBlattNr, String bezeichnung) {
        ContentValues values = new ContentValues();
        values.put("bezeichnung", bezeichnung);
        db.update(
                TABELLE_ZAEHLERLISTE,
                values,
                "id >= ? AND id <= ?",
                new String[]{String.valueOf(vonBlattNr), String.valueOf(bisBlattNr)}
        );
    }

    private void setzeBeispielBlattStatusWennNochNichtVorhanden(SQLiteDatabase db, int blattNr, boolean abgeschlossen) {
        if (ladeZaehlerlistenStatusInfo(db, blattNr) != null) {
            return;
        }

        ContentValues values = new ContentValues();
        values.put("abgeschlossen", abgeschlossen ? 1 : 0);
        db.update(
                TABELLE_ZAEHLERLISTE,
                values,
                "id = ?",
                new String[]{String.valueOf(blattNr)}
        );
        speichereZaehlerlistenStatus(db, blattNr, null, null, abgeschlossen);
    }

    private List<BestandszaehlungSeed> erstelleStandardSeedsFuerBlatt(int blattNr) {
        List<BestandszaehlungSeed> seeds = new ArrayList<>();
        for (int index = 1; index <= 5; index++) {
            int artikelIndex = ((blattNr + index - 9001) % 20) + 1;
            String identnummer = String.format("T%07d", artikelIndex);
            seeds.add(new BestandszaehlungSeed(
                    (blattNr * 10) + index,
                    index,
                    identnummer,
                    "R" + (((blattNr + index) - 9001) % 9 + 1),
                    25 + index,
                    5 + artikelIndex,
                    "Testartikel " + artikelIndex
            ));
        }
        return seeds;
    }

    private List<BestandszaehlungSeed> erstelleBeispielSeedsFuerTest(int blattNr) {
        if (blattNr < 9001 || (blattNr > 9006 && blattNr < 9010) || blattNr > 9015) {
            return null;
        }

        Integer[] gespeicherteZaehlmengen;
        switch (blattNr) {
            case 9001:
                gespeicherteZaehlmengen = new Integer[]{26, 27, null, null, null};
                break;
            case 9002:
                gespeicherteZaehlmengen = new Integer[]{24, 25, 26, 27, 28};
                break;
            case 9003:
                gespeicherteZaehlmengen = new Integer[]{null, null, null, null, null};
                break;
            case 9004:
                gespeicherteZaehlmengen = new Integer[]{29, 30, 31, 32, 33};
                break;
            case 9005:
                gespeicherteZaehlmengen = new Integer[]{21, 22, 23, 24, 25};
                break;
            case 9006:
                gespeicherteZaehlmengen = new Integer[]{18, 19, 20, 21, 22};
                break;
            case 9010:
                gespeicherteZaehlmengen = new Integer[]{15, null, null, null, null};
                break;
            case 9011:
                gespeicherteZaehlmengen = new Integer[]{17, 18, null, null, null};
                break;
            case 9012:
                gespeicherteZaehlmengen = new Integer[]{19, 20, 21, 22, 23};
                break;
            case 9013:
                gespeicherteZaehlmengen = new Integer[]{null, null, 16, null, null};
                break;
            case 9014:
                gespeicherteZaehlmengen = new Integer[]{14, 15, 16, 17, 18};
                break;
            case 9015:
                gespeicherteZaehlmengen = new Integer[]{null, null, null, null, null};
                break;
            default:
                return null;
        }

        List<BestandszaehlungSeed> standardSeeds = erstelleStandardSeedsFuerBlatt(blattNr);
        List<BestandszaehlungSeed> beispielSeeds = new ArrayList<>();
        for (int index = 0; index < standardSeeds.size(); index++) {
            BestandszaehlungSeed standardSeed = standardSeeds.get(index);
            Integer gespeicherteZaehlmenge = index < gespeicherteZaehlmengen.length
                    ? gespeicherteZaehlmengen[index]
                    : null;
            Integer letzteZaehlmenge = standardSeed.getIstMenge() - 1;
            beispielSeeds.add(new BestandszaehlungSeed(
                    standardSeed.getIdSql(),
                    standardSeed.getPositionsNummer(),
                    standardSeed.getIdentnummer(),
                    standardSeed.getLagerort(),
                    standardSeed.getIstMenge(),
                    gespeicherteZaehlmenge,
                    letzteZaehlmenge,
                    standardSeed.getMindestbestand(),
                    standardSeed.getBezeichnung()
            ));
        }
        return beispielSeeds;
    }

    private void sichereTeilestammBasisFallsFehlt(SQLiteDatabase db, BestandszaehlungSeed seed) {
        Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM " + TABELLE_TEILESTAMM + " WHERE identnummer = ?",
                new String[]{seed.getIdentnummer()}
        );
        try {
            if (cursor.moveToFirst() && cursor.getInt(0) > 0) {
                return;
            }
        } finally {
            cursor.close();
        }

        ContentValues values = new ContentValues();
        values.put("identnummer", seed.getIdentnummer());
        values.put("bezeichnung1", istLeer(seed.getBezeichnung()) ? "Artikel " + seed.getIdentnummer() : seed.getBezeichnung());
        values.put("bezeichnung2", "");
        values.put("mengeneinheit", "St");
        values.put("mengeneinheit_lager", "St");
        values.put("mindestbestand", seed.getMindestbestand());
        values.put("lager_ort", seed.getLagerort());
        values.put("lager_standorte", seed.getLagerort());
        db.insertWithOnConflict(TABELLE_TEILESTAMM, null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    private boolean istPlatzhalterName(String wert, String praefix) {
        if (wert == null) {
            return true;
        }
        String normalisiert = wert.trim().toLowerCase();
        return normalisiert.isEmpty() || normalisiert.startsWith(praefix.toLowerCase() + " ");
    }

    private boolean istLeer(String wert) {
        return wert == null || wert.trim().isEmpty();
    }

    private boolean existiertTabelle(SQLiteDatabase db, String tabellenname) {
        Cursor cursor = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                new String[]{tabellenname}
        );
        try {
            return cursor.moveToFirst();
        } finally {
            cursor.close();
        }
    }

    private void fuegeSpalteHinzuFallsFehlt(SQLiteDatabase db, String tabellenname, String spaltenDefinition) {
        String spaltenname = spaltenDefinition.split(" ")[0];
        Cursor cursor = db.rawQuery("PRAGMA table_info(" + tabellenname + ")", null);
        try {
            while (cursor.moveToNext()) {
                if (spaltenname.equalsIgnoreCase(cursor.getString(cursor.getColumnIndexOrThrow("name")))) {
                    return;
                }
            }
        } finally {
            cursor.close();
        }
        db.execSQL("ALTER TABLE " + tabellenname + " ADD COLUMN " + spaltenDefinition);
    }

    private int zaehleDatensaetze(SQLiteDatabase db, String tabellenname) {
        Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + tabellenname, null);
        try {
            if (!cursor.moveToFirst()) {
                return 0;
            }
            return cursor.getInt(0);
        } finally {
            cursor.close();
        }
    }

    private String kombiniereBezeichnung(String bezeichnung1, String bezeichnung2, String identnummer) {
        String ersterTeil = bezeichnung1 == null ? "" : bezeichnung1.trim();
        String zweiterTeil = bezeichnung2 == null ? "" : bezeichnung2.trim();
        String kombiniert = (ersterTeil + " " + zweiterTeil).trim();
        if (!kombiniert.isEmpty()) {
            return kombiniert;
        }
        return identnummer == null || identnummer.trim().isEmpty()
                ? "Bezeichnung nicht verfuegbar"
                : "Artikel " + identnummer.trim();
    }

    private Integer leseGanzzahlOderNull(Cursor cursor, String spaltenname, Integer standardwert) {
        int spaltenIndex = cursor.getColumnIndexOrThrow(spaltenname);
        if (cursor.isNull(spaltenIndex)) {
            return standardwert;
        }
        return Math.round(cursor.getFloat(spaltenIndex));
    }
}
