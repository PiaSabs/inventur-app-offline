package com.example.myapplication;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSetMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// Wiederhergestellter direkter SQL-Zugriff. Der Klassenname bleibt fuer die
// bestehenden Aufrufer erhalten; dies ist keine REST-Anbindung.
public class ApiInventurRemoteDataSource implements InventurRemoteDataSource {

    private static final int DIAGNOSE_BLATT_NR = 9001;
    private static final String DIAGNOSE_IDENT_NR = "T0000001";
    private static final String TABELLE_BLATTTEXTE = "Z\u00E4hllisten__Blatttexte";
    private static final String TABELLE_ZAEHLLISTE = "Z\u00E4hlliste";
    private static final String[] TABELLEN_BESTANDSZAEHLUNGEN = {
            "Bestandsz\u00E4hlungen",
            "Bestandszaehlungen",
            "Bestandsz\u00E4hlung2",
            "Bestandszaehlung2",
            "Bestandsz\u00E4hlungen2",
            "Bestandszaehlungen2"
    };

    private final LokalerInventurSpeicher lokalerInventurSpeicher;
    private String letzteDiagnose = "";

    public ApiInventurRemoteDataSource(LokalerInventurSpeicher lokalerInventurSpeicher) {
        this.lokalerInventurSpeicher = lokalerInventurSpeicher;
    }

    @Override
    public boolean istSchreibgeschuetzt() { return !SqlServerSchreibschutz.istSchreibenErlaubt(); }

    public SqlServerMengenSchreibversuch kontrollierterMengenversuch() throws SQLException {
        SqlServerSchreibschutz.pruefeKontrolliertenEinzelversuch();
        return new SqlServerMengenSchreibversuch(() -> {
            SqlServerSchreibschutz.pruefeKontrolliertenEinzelversuch();
            return oeffneJdbcVerbindung();
        }, true);
    }

    @Override
    public void pruefeVerbindung() throws RemoteDatenException {
        pruefeKonfiguration();
        try (Connection connection = oeffneVerbindung();
             PreparedStatement statement = connection.prepareStatement("SELECT 1");
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new RemoteDatenException("SQL-Server-Verbindungspruefung lieferte kein Ergebnis.");
            }
        } catch (SQLException exception) {
            throw new RemoteDatenException("SQL-Server-Verbindungspruefung fehlgeschlagen.", exception);
        }
    }

    @Override
    public List<ZaehlerlisteDto> ladeZaehlerlisten() throws RemoteDatenException {
        return ladeListenkoepfe(true);
    }

    @Override
    public List<ZaehlerlisteDto> ladeListenuebersicht() throws RemoteDatenException {
        return ladeListenkoepfe(false);
    }

    private List<ZaehlerlisteDto> ladeListenkoepfe(boolean mitZaehlstichtag) throws RemoteDatenException {
        pruefeKonfiguration();

        String sql = "SELECT b.*, i.InvBereichName AS BereichName, i.[Z\u00e4hler] AS Zaehler, i.Schreiber AS Schreiber "
                + "FROM " + qualifizierterTabellenname(TABELLE_BLATTTEXTE) + " b LEFT JOIN "
                + qualifizierterTabellenname("Inventur_Bereiche") + " i ON i.InvBereichNr=b.InvBereichNr "
                + "ORDER BY b.BlattNrIndex";

        List<ZaehlerlisteDto> zaehlerlisten = new ArrayList<>();
        try (Connection connection = oeffneVerbindung();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {

            while (resultSet.next()) {
                int blattNr = resultSet.getInt("BlattNrIndex");
                Timestamp letzteZaehlung = resultSet.getTimestamp("LetzteZaehlung");
                int inventurBereichNr = resultSet.getInt("InvBereichNr");
                boolean hatInventurBereich = !resultSet.wasNull();
                String zaehlTyp = liesText(resultSet, "ZaehlTyp", "");

                if (!istAktiverZaehTyp(zaehlTyp)) {
                    continue;
                }
                if (!hatInventurBereich) {
                    continue;
                }

                LokalerInventurSpeicher.InventurBereichInfo inventurBereich = null;

                // Die Uebersicht liest keine Artikel oder lokal abgeleiteten Zaehlstichtage.
                String stichtag = mitZaehlstichtag ? ermittleOderInitialisiereStichtag(blattNr) : "";

                zaehlerlisten.add(new ZaehlerlisteDto(
                        blattNr,
                        liesText(resultSet, "Bezeichnung", "Zaehlliste " + blattNr),
                        String.valueOf(blattNr),
                        bestimmeBereich(resultSet, inventurBereich),
                        formatiereAnzeigeDatum(stichtag),
                        formatiereAnzeigeDatum(letzteZaehlung),
                        istFaellig(letzteZaehlung),
                        false,
                        liesText(resultSet, "Zaehler", ""),
                        liesText(resultSet, "Schreiber", ""),
                        zaehlTyp
                ));
            }
            return zaehlerlisten;
        } catch (SQLException exception) {
            throw new RemoteDatenException("SQL-Server-Laden der aktiven Zaehlerlisten fehlgeschlagen.", exception);
        }
    }

    @Override
    public List<ArtikelDto> ladeArtikel(int zaehlerlistenId) throws RemoteDatenException {
        pruefeKonfiguration();

        String stichtag = ermittleOderInitialisiereStichtag(zaehlerlistenId);
        List<ArtikelDto> artikelListe = new ArrayList<>();
        SqlServerMengenSchreibversuch leser = new SqlServerMengenSchreibversuch(this::oeffneVerbindung, true);
        for (LokalerInventurSpeicher.BestandszaehlungSeed seed : ladeSeedsAusSqlZaehlliste(zaehlerlistenId, stichtag)) {
            ArtikelDto artikel=mappeSqlPosition(seed, zaehlerlistenId);
            try {
                artikel=artikel.mitSqlErgebnis(leser.leseAmStichtag(zaehlerlistenId,artikel.getPositionsNummer(),stichtag));
            } catch(SQLException e) {
                if(!"23000".equals(e.getSQLState()))throw new RemoteDatenException("SQL-Ergebniszuordnung konnte nicht vollstaendig geladen werden.",e);
                // Fehlende oder mehrdeutige Ergebnisse bleiben lesbar, aber ohne Schreibfreigabe.
            } catch(IllegalArgumentException|ArithmeticException e) {
                // Keine gerundete oder widerspruechliche Menge als SQL-Ausgangsstand verwenden.
            }
            artikelListe.add(artikel);
        }
        aktualisiereLadeDiagnose(zaehlerlistenId, stichtag, artikelListe);
        return artikelListe;
    }

    static ArtikelDto mappeSqlPosition(LokalerInventurSpeicher.BestandszaehlungSeed seed, int blattNr) {
        // Der Seed stammt hier aus der Positionsabfrage, nicht aus Bestandszaehlungen.
        return new ArtikelDto(seed.getPositionsNummer(), seed.getIdentnummer(), seed.getBezeichnung(),
                seed.getLagerort(), seed.getIstMenge(), seed.getLetzteZaehlmenge(), seed.getMindestbestand(),
                seed.getGespeicherteZaehlmenge(), "", (long) seed.getIdSql(), blattNr);
    }

    @Override
    public List<SqlPosition> ladeSqlPositionen(int blattId) throws RemoteDatenException {
        pruefeKonfiguration();
        String sql = "SELECT IDSQL, BlattNr, PosNr, IdentNr FROM "
                + qualifizierterTabellenname(TABELLE_ZAEHLLISTE) + " WHERE BlattNr = ?";
        List<SqlPosition> positionen = new ArrayList<>();
        try (Connection connection = oeffneVerbindung();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, blattId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    long id = rows.getLong("IDSQL");
                    Long quellId = rows.wasNull() ? null : id;
                    int blatt = rows.getInt("BlattNr");
                    Integer quellBlatt = rows.wasNull() ? null : blatt;
                    int position = rows.getInt("PosNr");
                    Integer quellPosition = rows.wasNull() ? null : position;
                    // Auch unvollstaendige Zeilen melden; keine Ersatzidentitaeten bilden.
                    positionen.add(new SqlPosition(quellId, quellBlatt, quellPosition, rows.getString("IdentNr")));
                }
            }
            return positionen;
        } catch (SQLException exception) {
            throw new RemoteDatenException("SQL-Positionspruefung fehlgeschlagen.", exception);
        }
    }

    @Override
    public ZaehlmengenSpeicherAntwort speichereZaehlmengen(ZaehlmengenSpeicherAnfrage anfrage) throws RemoteDatenException {
        SqlServerSchreibschutz.pruefeSchreiben();
        throw new RemoteDatenException("Pauschale SQL-Listenuebertragung ist deaktiviert.");
    }

    @Override
    public void markiereZaehlerlisteAlsAbgeschlossen(int zaehlerlistenId) throws RemoteDatenException {
        SqlServerSchreibschutz.pruefeSchreiben();
    }

    @Override
    public String holeLetzteDiagnose() {
        return letzteDiagnose == null ? "" : letzteDiagnose;
    }

    private Connection oeffneVerbindung() throws SQLException {
        DriverManager.setLoginTimeout(3);
        return SqlServerSchreibschutz.sichere(oeffneJdbcVerbindung());
    }

    private Connection oeffneJdbcVerbindung() throws SQLException {
        try {
            Class.forName("net.sourceforge.jtds.jdbc.Driver");
        } catch (ClassNotFoundException exception) {
            throw new SQLException("JDBC-Treiber fehlt.", "08001");
        }
        String url = "jdbc:jtds:sqlserver://" + SqlServerKonfiguration.HOST + ":"
                + SqlServerKonfiguration.PORT + "/" + SqlServerKonfiguration.DATENBANK
                + ";useLOBs=false;loginTimeout=5;socketTimeout=15;";
        return DriverManager.getConnection(url,
                SqlServerKonfiguration.BENUTZERNAME, SqlServerKonfiguration.PASSWORT);
    }

    private void pruefeKonfiguration() throws RemoteDatenException {
        if (!SqlServerKonfiguration.istVollstaendigKonfiguriert()) {
            throw new RemoteDatenException("SQL-Server-Konfiguration ist unvollstaendig.");
        }
    }

    private String qualifizierterTabellenname(String tabellenname) {
        return qualifizierterTabellenname(SqlServerKonfiguration.SCHEMA, tabellenname);
    }

    private String qualifizierterTabellenname(String schema, String tabellenname) {
        return maskiereSqlBezeichner(schema) + "." + maskiereSqlBezeichner(tabellenname);
    }

    private String maskiereSqlBezeichner(String bezeichner) {
        return "[" + bezeichner.replace("]", "]]") + "]";
    }

    private String liesText(ResultSet resultSet, String spaltenname, String standardwert) throws SQLException {
        String wert = resultSet.getString(spaltenname);
        if (wert == null || wert.trim().isEmpty()) {
            return standardwert;
        }
        return wert.trim();
    }

    private String bestimmeBereich(ResultSet resultSet, LokalerInventurSpeicher.InventurBereichInfo inventurBereich) throws SQLException {
        String bereich = liesText(resultSet, "BereichName", "");
        if (!bereich.isEmpty()) {
            return bereich;
        }
        if (inventurBereich != null && !inventurBereich.getInventurBereichName().trim().isEmpty()) {
            return inventurBereich.getInventurBereichName();
        }

        String ort = liesText(resultSet, "Ort", "");
        if (!ort.isEmpty()) {
            return ort;
        }

        String firma = liesText(resultSet, "Firma", "");
        if (!firma.isEmpty()) {
            return firma;
        }
        return "Unbekannter Bereich";
    }

    private String ermittleOderInitialisiereStichtag(int blattNr) throws RemoteDatenException {
        Zaehlerliste lokaleListe = lokalerInventurSpeicher.ladeZaehlerlisteFuerId(blattNr);
        if (lokaleListe != null && lokaleListe.getStichtag() != null && !lokaleListe.getStichtag().trim().isEmpty()) {
            return parseSqlDatumNachSqlLite(lokaleListe.getStichtag());
        }

        String sqlStichtag = ladeAktuellenStichtagAusSqlServer(blattNr);
        if (sqlStichtag != null) {
            return sqlStichtag;
        }

        String heutigerStichtag = ermittleHeutigesDatum();
        return heutigerStichtag;
    }

    private String ladeAktuellenStichtagAusSqlServer(int blattNr) throws RemoteDatenException {
        try (Connection connection = oeffneVerbindung()) {
            BestandszaehlungenSqlInfo bestandszaehlungenInfo = ermittleBestandszaehlungenSqlInfo(connection);
            String sql = "SELECT MAX(" + bestandszaehlungenInfo.datum + ") FROM " + bestandszaehlungenInfo.tabellenname
                    + " WHERE " + bestandszaehlungenInfo.blattNr + " = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, blattNr);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next() || resultSet.getObject(1) == null) {
                        return null;
                    }
                    Timestamp zeitstempel = resultSet.getTimestamp(1);
                    if (zeitstempel != null) {
                        return new SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(new Date(zeitstempel.getTime()));
                    }
                    String rohwert = resultSet.getString(1);
                    return parseSqlDatumNachSqlLite(rohwert);
                }
            }
        } catch (SQLException exception) {
            throw new RemoteDatenException("SQL-Server-Stichtag fuer Blatt " + blattNr + " konnte nicht geladen werden.", exception);
        }
    }

    private List<LokalerInventurSpeicher.BestandszaehlungSeed> ladeSeedsAusSqlZaehlliste(int blattNr, String stichtag) throws RemoteDatenException {
        String sql = "SELECT z.*, t.Bezeichnung1, "
                + "CASE WHEN CONVERT(varchar(100),t.HinweisIntern)='ZaehlerApp-Demo-v1' "
                + "THEN t.Bezeichnung2 ELSE z.Lagerort END AS AnzeigeLagerort "
                + "FROM " + qualifizierterTabellenname(TABELLE_ZAEHLLISTE) + " z LEFT JOIN "
                + qualifizierterTabellenname("Teilestamm") + " t ON t.Identnummer=z.IdentNr "
                + "WHERE z.BlattNr = ? ORDER BY z.PosNr";

        List<LokalerInventurSpeicher.BestandszaehlungSeed> seeds = new ArrayList<>();
        try (Connection connection = oeffneVerbindung();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            BestandszaehlungenSqlInfo bestandszaehlungenInfo = ermittleBestandszaehlungenSqlInfo(connection);
            String aktuelleZaehlmengeSql = "SELECT TOP 1 " + bestandszaehlungenInfo.mengeLager + ", " + bestandszaehlungenInfo.menge + " FROM " + bestandszaehlungenInfo.tabellenname + " "
                    + "WHERE " + bestandszaehlungenInfo.blattNr + " = ? AND " + bestandszaehlungenInfo.posNr + " = ? AND " + bestandszaehlungenInfo.identNrBestand + " = ? "
                    + "AND " + bestandszaehlungenInfo.datum + " = ? "
                    + "ORDER BY " + bestandszaehlungenInfo.datum + " DESC";
            String letzteZaehlmengeSql = "SELECT TOP 1 " + bestandszaehlungenInfo.mengeLager + " FROM " + bestandszaehlungenInfo.tabellenname + " "
                    + "WHERE " + bestandszaehlungenInfo.blattNr + " = ? AND " + bestandszaehlungenInfo.posNr + " = ? AND " + bestandszaehlungenInfo.identNrBestand + " = ? "
                    + "AND " + bestandszaehlungenInfo.datum + " < ? AND " + bestandszaehlungenInfo.mengeLager + " IS NOT NULL "
                    + "ORDER BY " + bestandszaehlungenInfo.datum + " DESC";
            try (PreparedStatement aktuelleZaehlmengeStatement = connection.prepareStatement(aktuelleZaehlmengeSql);
                 PreparedStatement letzteZaehlmengeStatement = connection.prepareStatement(letzteZaehlmengeSql)) {
            statement.setInt(1, blattNr);
            try (ResultSet resultSet = statement.executeQuery()) {
                int laufendeNummer = 0;
                while (resultSet.next()) {
                    laufendeNummer++;
                    String identNr = liesText(resultSet, "IdentNr", "");
                    String lagerort = liesText(resultSet, "AnzeigeLagerort", "0");
                    if (lagerort.isEmpty()) {
                        lagerort = "0";
                    }
                    int positionsNummer = resultSet.getInt("PosNr");
                    String aufgeloesteIdentnummer = identNr.trim();
                    if (aufgeloesteIdentnummer.isEmpty()) {
                        // Leere SQL-Platzhalter sind keine Artikel; keine Testnummer erfinden.
                        continue;
                    }
                    int mindestbestand = resultSet.getInt("MinBestand");
                    if (resultSet.wasNull()) {
                        mindestbestand = 0;
                    }
                    Integer[] aktuelleMengen = ladeAktuelleMengen(
                            aktuelleZaehlmengeStatement,
                            blattNr,
                            positionsNummer <= 0 ? laufendeNummer : positionsNummer,
                            aufgeloesteIdentnummer,
                            stichtag
                    );
                    Integer letzteZaehlmenge = ladeLetzteZaehlmenge(
                            letzteZaehlmengeStatement,
                            blattNr,
                            positionsNummer <= 0 ? laufendeNummer : positionsNummer,
                            aufgeloesteIdentnummer,
                            stichtag
                    );

                    seeds.add(new LokalerInventurSpeicher.BestandszaehlungSeed(
                            resultSet.getInt("IDSQL"),
                            positionsNummer <= 0 ? laufendeNummer : positionsNummer,
                            aufgeloesteIdentnummer,
                            lagerort,
                            aktuelleMengen[1],
                            aktuelleMengen[0],
                            letzteZaehlmenge,
                            mindestbestand,
                            liesText(resultSet, "Bezeichnung1", "Artikel " + aufgeloesteIdentnummer)
                    ));
                }
            }
            }
        } catch (SQLException exception) {
            throw new RemoteDatenException("SQL-Server-Positionen fuer Blatt " + blattNr + " konnten nicht geladen werden.", exception);
        }

        return seeds;
    }

    private String formatiereAnzeigeDatum(String sqlLiteDatum) {
        try {
            Date datum = new SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).parse(sqlLiteDatum);
            return datum == null ? "unbekannt" : new SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(datum);
        } catch (Exception exception) {
            return sqlLiteDatum == null ? "unbekannt" : sqlLiteDatum;
        }
    }

    private String formatiereAnzeigeDatum(Timestamp zeitstempel) {
        if (zeitstempel == null) {
            return "unbekannt";
        }
        return new SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(new Date(zeitstempel.getTime()));
    }


    private Integer ladeLetzteZaehlmenge(
            PreparedStatement statement,
            int blattNr,
            int positionsNummer,
            String identnummer,
            String stichtag
    ) throws SQLException {
        statement.setInt(1, blattNr);
        statement.setInt(2, positionsNummer);
        statement.setString(3, identnummer);
        statement.setTimestamp(4, Timestamp.valueOf(stichtag+" 00:00:00"));
        try (ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next() || resultSet.getObject(1) == null) {
                return null;
            }
            return Math.round(resultSet.getFloat(1));
        }
    }

    private Integer[] ladeAktuelleMengen(
            PreparedStatement statement,
            int blattNr,
            int positionsNummer,
            String identnummer,
            String stichtag
    ) throws SQLException {
        statement.setInt(1, blattNr);
        statement.setInt(2, positionsNummer);
        statement.setString(3, identnummer);
        statement.setTimestamp(4, Timestamp.valueOf(stichtag+" 00:00:00"));
        try (ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return new Integer[]{null, 0};
            }
            Integer zaehlmenge = resultSet.getObject(1) == null ? null : Math.round(resultSet.getFloat(1));
            int bestand = resultSet.getObject(2) == null ? 0 : Math.round(resultSet.getFloat(2));
            return new Integer[]{zaehlmenge, bestand};
        }
    }

    private boolean istFaellig(Timestamp letzteZaehlung) {
        if (letzteZaehlung == null) {
            return true;
        }
        long differenzMillis = new Date().getTime() - letzteZaehlung.getTime();
        long tage = differenzMillis / (24L * 60L * 60L * 1000L);
        return tage >= 30L;
    }

    private String ermittleHeutigesDatum() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(new Date());
    }

    static String parseSqlDatumNachSqlLite(String rohwert) {
        if (rohwert == null || rohwert.trim().isEmpty()) {
            return null;
        }
        String bereinigt = rohwert.trim();
        if (bereinigt.matches("\\d{2}\\.\\d{2}\\.\\d{4}")) {
            return bereinigt.substring(6, 10) + "-" + bereinigt.substring(3, 5) + "-" + bereinigt.substring(0, 2);
        }
        if (bereinigt.length() >= 10) {
            return bereinigt.substring(0, 10);
        }
        return bereinigt;
    }

    private boolean istAktiverZaehTyp(String zaehlTyp) {
        String normalisiert = zaehlTyp == null ? "" : zaehlTyp.trim().toUpperCase(Locale.GERMANY);
        return "WO".equals(normalisiert) || "BC".equals(normalisiert) || "MO".equals(normalisiert);
    }


    private BestandszaehlungenSqlInfo ermittleBestandszaehlungenSqlInfo(Connection connection) throws SQLException {
        Set<String> kandidaten = neueBestandszaehlungenTabellenKandidaten();
        fuegeBestandszaehlungenKandidatenAusMetadatenHinzu(connection, kandidaten);

        SQLException letzterFehler = null;
        for (String tabellenname : kandidaten) {
            try {
                return leseBestandszaehlungenSqlInfo(connection, tabellenname);
            } catch (SQLException exception) {
                letzterFehler = exception;
            }
        }

        SQLException fehler = new SQLException("Keine passende Bestandszaehlungen-Tabelle auf dem SQL-Server gefunden.");
        if (letzterFehler != null) {
            fehler.addSuppressed(letzterFehler);
        }
        throw fehler;
    }

    private Set<String> neueBestandszaehlungenTabellenKandidaten() {
        Set<String> kandidaten = new LinkedHashSet<>();
        for (String tabellenname : TABELLEN_BESTANDSZAEHLUNGEN) {
            kandidaten.add(qualifizierterTabellenname(tabellenname));
            kandidaten.add(maskiereSqlBezeichner(tabellenname));
        }
        return kandidaten;
    }

    private void fuegeBestandszaehlungenKandidatenAusMetadatenHinzu(Connection connection, Set<String> kandidaten) {
        String sql =
                "SELECT schema_name, object_name FROM (" +
                        "SELECT s.name AS schema_name, o.name AS object_name, CASE WHEN s.name = ? THEN 0 ELSE 1 END AS schema_prio " +
                        "FROM sys.objects o " +
                        "INNER JOIN sys.schemas s ON s.schema_id = o.schema_id " +
                        "WHERE o.type IN ('U', 'V') AND o.name IN (?, ?) " +
                        "UNION ALL " +
                        "SELECT s.name AS schema_name, sy.name AS object_name, CASE WHEN s.name = ? THEN 0 ELSE 1 END AS schema_prio " +
                        "FROM sys.synonyms sy " +
                        "INNER JOIN sys.schemas s ON s.schema_id = sy.schema_id " +
                        "WHERE sy.name IN (?, ?) " +
                        ") kandidaten " +
                        "ORDER BY schema_prio, schema_name, object_name";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, SqlServerKonfiguration.SCHEMA);
            statement.setString(2, TABELLEN_BESTANDSZAEHLUNGEN[0]);
            statement.setString(3, TABELLEN_BESTANDSZAEHLUNGEN[1]);
            statement.setString(4, SqlServerKonfiguration.SCHEMA);
            statement.setString(5, TABELLEN_BESTANDSZAEHLUNGEN[0]);
            statement.setString(6, TABELLEN_BESTANDSZAEHLUNGEN[1]);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String schema = liesText(resultSet, "schema_name", "");
                    String objektname = liesText(resultSet, "object_name", "");
                    if (!schema.isEmpty() && !objektname.isEmpty()) {
                        kandidaten.add(qualifizierterTabellenname(schema, objektname));
                    }
                }
            }
        } catch (SQLException exception) {
            // Direkter Tabellenzugriff ist wichtiger als Metadatenzugriff.
        }
    }

    private BestandszaehlungenSqlInfo leseBestandszaehlungenSqlInfo(Connection connection, String tabellenname) throws SQLException {
        String sql = "SELECT TOP 0 * FROM " + tabellenname;
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            ResultSetMetaData metaData = resultSet.getMetaData();
            List<String> spaltennamen = new ArrayList<>();
            for (int index = 1; index <= metaData.getColumnCount(); index++) {
                String spaltenname = metaData.getColumnName(index);
                spaltennamen.add(spaltenname);
            }
            return new BestandszaehlungenSqlInfo(
                    tabellenname,
                    findeSpaltennamen(spaltennamen, "ident_nr_bestand", "identnrbestand", "ident_nr", "identnr"),
                    findeSpaltennamen(spaltennamen, "datum"),
                    findeSpaltennamen(spaltennamen, "menge"),
                    findeSpaltennamen(spaltennamen, "blatt_nr", "blattnr"),
                    findeSpaltennamen(spaltennamen, "pos_nr", "posnr"),
                    findeSpaltennamen(spaltennamen, "menge_lager", "mengelager")
            );
        }
    }

    private String findeSpaltennamen(List<String> vorhandeneSpalten, String... aliasNamen) throws SQLException {
        for (String vorhandeneSpalte : vorhandeneSpalten) {
            String normalisierteVorhandeneSpalte = normalisiereSqlNamen(vorhandeneSpalte);
            for (String aliasName : aliasNamen) {
                if (normalisierteVorhandeneSpalte.equals(normalisiereSqlNamen(aliasName))) {
                    return maskiereSqlBezeichner(vorhandeneSpalte);
                }
            }
        }
        throw new SQLException("Erforderliche Spalte wurde nicht gefunden: " + aliasNamen[0]);
    }

    private String normalisiereSqlNamen(String wert) {
        if (wert == null) {
            return "";
        }
        return wert
                .trim()
                .toLowerCase(Locale.GERMANY)
                .replace("ä", "ae")
                .replace("ö", "oe")
                .replace("ü", "ue")
                .replace("ß", "ss")
                .replace("_", "")
                .replace(" ", "");
    }

    private void aktualisiereLadeDiagnose(int blattNr, String stichtag, List<ArtikelDto> artikelListe) {
        if (blattNr != DIAGNOSE_BLATT_NR || artikelListe == null) {
            return;
        }

        ArtikelDto zielArtikel = null;
        for (ArtikelDto artikel : artikelListe) {
            if (artikel == null) {
                continue;
            }
            if (DIAGNOSE_IDENT_NR.equalsIgnoreCase(artikel.getArtikelnummer())) {
                zielArtikel = artikel;
                break;
            }
        }

        StringBuilder diagnose = new StringBuilder();
        diagnose.append("SQL-Ladediagnose\n");
        diagnose.append("Blatt: ").append(blattNr).append('\n');
        diagnose.append("Stichtag lokal: ").append(stichtag).append('\n');
        if (zielArtikel == null) {
            diagnose.append("Artikel ").append(DIAGNOSE_IDENT_NR).append(" wurde lokal nicht gefunden.");
        } else {
            diagnose.append("Artikel: ").append(zielArtikel.getArtikelnummer()).append('\n');
            diagnose.append("Pos: ").append(zielArtikel.getPositionsNummer()).append('\n');
            diagnose.append("Lagerort: ").append(zielArtikel.getLagerort()).append('\n');
            diagnose.append("Ist-Menge: ").append(zielArtikel.getIstMenge()).append('\n');
            diagnose.append("Letzte Zaehlmenge: ").append(formatiereDiagnoseWert(zielArtikel.getLetzteZaehlmenge())).append('\n');
            diagnose.append("Aktuelle Zaehlmenge lokal: ").append(formatiereDiagnoseWert(zielArtikel.getZaehlMenge()));
        }
        letzteDiagnose = diagnose.toString();
    }

    private String formatiereDiagnoseWert(Integer wert) {
        return wert == null ? "NULL" : String.valueOf(wert);
    }

    private static final class BestandszaehlungenSqlInfo {
        private final String tabellenname;
        private final String identNrBestand;
        private final String datum;
        private final String menge;
        private final String blattNr;
        private final String posNr;
        private final String mengeLager;

        private BestandszaehlungenSqlInfo(
                String tabellenname,
                String identNrBestand,
                String datum,
                String menge,
                String blattNr,
                String posNr,
                String mengeLager
        ) {
            this.tabellenname = tabellenname;
            this.identNrBestand = identNrBestand;
            this.datum = datum;
            this.menge = menge;
            this.blattNr = blattNr;
            this.posNr = posNr;
            this.mengeLager = mengeLager;
        }
    }
}
