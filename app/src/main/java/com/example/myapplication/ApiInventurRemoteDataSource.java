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
    private static final SimpleDateFormat ANZEIGE_DATUMSFORMAT = new SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY);
    private static final SimpleDateFormat SQL_LITE_DATUMSFORMAT = new SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY);

    private final LokalerInventurSpeicher lokalerInventurSpeicher;
    private String letzteDiagnose = "";

    public ApiInventurRemoteDataSource(LokalerInventurSpeicher lokalerInventurSpeicher) {
        this.lokalerInventurSpeicher = lokalerInventurSpeicher;
    }

    @Override
    public boolean istSchreibgeschuetzt() { return !SqlServerSchreibschutz.istSchreibenErlaubt(); }

    @Override
    public void pruefeVerbindung() throws RemoteDatenException {
        pruefeKonfiguration();
        try (Connection connection = oeffneVerbindung();
             PreparedStatement statement = connection.prepareStatement("SELECT 1");
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new RemoteDatenException("SQL-Server-Verbindungspruefung lieferte kein Ergebnis.");
            }
        } catch (SQLException | ClassNotFoundException exception) {
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
        } catch (SQLException | ClassNotFoundException exception) {
            throw new RemoteDatenException("SQL-Server-Laden der aktiven Zaehlerlisten fehlgeschlagen.", exception);
        }
    }

    @Override
    public List<ArtikelDto> ladeArtikel(int zaehlerlistenId) throws RemoteDatenException {
        pruefeKonfiguration();

        String stichtag = ermittleOderInitialisiereStichtag(zaehlerlistenId);
        List<ArtikelDto> artikelListe = new ArrayList<>();
        for (LokalerInventurSpeicher.BestandszaehlungSeed seed : ladeSeedsAusSqlZaehlliste(zaehlerlistenId, stichtag)) {
            artikelListe.add(mappeSqlPosition(seed, zaehlerlistenId));
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
    public ZaehlmengenSpeicherAntwort speichereZaehlmengen(ZaehlmengenSpeicherAnfrage anfrage) throws RemoteDatenException {
        SqlServerSchreibschutz.pruefeSchreiben();
        if (anfrage == null || anfrage.getZaehlerlistenId() <= 0 || anfrage.getPositionen() == null || anfrage.getPositionen().isEmpty()) {
            throw new RemoteDatenException("Die Zaehlmengen-Anfrage ist unvollstaendig.");
        }

        String stichtag = ermittleOderInitialisiereStichtag(anfrage.getZaehlerlistenId());

        pruefeKonfiguration();
        speichereZaehlmengenInSqlServer(anfrage, stichtag);
        return new ZaehlmengenSpeicherAntwort(
                true,
                "In Datenbank gespeichert. Stichtag: " + formatiereAnzeigeDatum(stichtag),
                "BESTANDS-" + anfrage.getZaehlerlistenId() + "-" + stichtag
        );
    }

    @Override
    public void markiereZaehlerlisteAlsAbgeschlossen(int zaehlerlistenId) throws RemoteDatenException {
        SqlServerSchreibschutz.pruefeSchreiben();
    }

    @Override
    public String holeLetzteDiagnose() {
        return letzteDiagnose == null ? "" : letzteDiagnose;
    }

    private Connection oeffneVerbindung() throws SQLException, ClassNotFoundException {
        Class.forName("net.sourceforge.jtds.jdbc.Driver");
        DriverManager.setLoginTimeout(3);
        String url = "jdbc:jtds:sqlserver://"
                + SqlServerKonfiguration.HOST
                + ":"
                + SqlServerKonfiguration.PORT
                + "/"
                + SqlServerKonfiguration.DATENBANK
                + ";useLOBs=false;loginTimeout=5;socketTimeout=15;";
        return SqlServerSchreibschutz.sichere(DriverManager.getConnection(url,
                SqlServerKonfiguration.BENUTZERNAME, SqlServerKonfiguration.PASSWORT));
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
                        return SQL_LITE_DATUMSFORMAT.format(new Date(zeitstempel.getTime()));
                    }
                    String rohwert = resultSet.getString(1);
                    return parseSqlDatumNachSqlLite(rohwert);
                }
            }
        } catch (SQLException | ClassNotFoundException exception) {
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
        } catch (SQLException | ClassNotFoundException exception) {
            throw new RemoteDatenException("SQL-Server-Positionen fuer Blatt " + blattNr + " konnten nicht geladen werden.", exception);
        }

        return seeds;
    }

    private String formatiereAnzeigeDatum(String sqlLiteDatum) {
        try {
            Date datum = SQL_LITE_DATUMSFORMAT.parse(sqlLiteDatum);
            return datum == null ? "unbekannt" : ANZEIGE_DATUMSFORMAT.format(datum);
        } catch (Exception exception) {
            return sqlLiteDatum == null ? "unbekannt" : sqlLiteDatum;
        }
    }

    private String formatiereAnzeigeDatum(Timestamp zeitstempel) {
        if (zeitstempel == null) {
            return "unbekannt";
        }
        return ANZEIGE_DATUMSFORMAT.format(new Date(zeitstempel.getTime()));
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
        statement.setString(4, stichtag);
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
        statement.setString(4, stichtag);
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
        return SQL_LITE_DATUMSFORMAT.format(new Date());
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

    private boolean istBeispielBlatt(int blattNr) {
        return blattNr >= 9001 && blattNr <= 9020;
    }

    private void speichereZaehlmengenInSqlServer(ZaehlmengenSpeicherAnfrage anfrage, String stichtag) throws RemoteDatenException {
        SqlServerSchreibschutz.pruefeSchreiben();
        try (Connection connection = oeffneVerbindung()) {
            connection.setAutoCommit(false);
            try {
                BestandszaehlungenSqlInfo bestandszaehlungenInfo = ermittleBestandszaehlungenSqlInfo(connection);
                List<Artikel> lokaleArtikel = lokalerInventurSpeicher.ladeArtikel(anfrage.getZaehlerlistenId());

                String quelleSqlNachPosition = "SELECT PosNr, IdentNr, Lagerort, IDSQL FROM "
                        + qualifizierterTabellenname(TABELLE_ZAEHLLISTE)
                        + " WHERE BlattNr = ? AND PosNr = ?";
                String quelleSqlNachIdent = "SELECT TOP 1 PosNr, IdentNr, Lagerort, IDSQL FROM "
                        + qualifizierterTabellenname(TABELLE_ZAEHLLISTE)
                        + " WHERE BlattNr = ? AND LTRIM(RTRIM(COALESCE(IdentNr, ''))) = ?"
                        + " ORDER BY PosNr";

                String insertSpalten = bestandszaehlungenInfo.idSqlIstIdentity
                        ? bestandszaehlungenInfo.identNrBestand + ", " + bestandszaehlungenInfo.datum + ", " + bestandszaehlungenInfo.lagerort + ", "
                        + bestandszaehlungenInfo.menge + ", " + bestandszaehlungenInfo.blattNr + ", " + bestandszaehlungenInfo.posNr + ", " + bestandszaehlungenInfo.mengeLager + ", "
                        + bestandszaehlungenInfo.zaehlNr + ", " + bestandszaehlungenInfo.hinweis + ", " + bestandszaehlungenInfo.gesamtMenge + ", " + bestandszaehlungenInfo.mengeOffen + ", "
                        + bestandszaehlungenInfo.bestellMenge + ", " + bestandszaehlungenInfo.bedarf + ", " + bestandszaehlungenInfo.geprueftKz + ", " + bestandszaehlungenInfo.datInventur
                        : bestandszaehlungenInfo.idSql + ", " + bestandszaehlungenInfo.identNrBestand + ", " + bestandszaehlungenInfo.datum + ", " + bestandszaehlungenInfo.lagerort + ", "
                        + bestandszaehlungenInfo.menge + ", " + bestandszaehlungenInfo.blattNr + ", " + bestandszaehlungenInfo.posNr + ", " + bestandszaehlungenInfo.mengeLager + ", "
                        + bestandszaehlungenInfo.zaehlNr + ", " + bestandszaehlungenInfo.hinweis + ", " + bestandszaehlungenInfo.gesamtMenge + ", " + bestandszaehlungenInfo.mengeOffen + ", "
                        + bestandszaehlungenInfo.bestellMenge + ", " + bestandszaehlungenInfo.bedarf + ", " + bestandszaehlungenInfo.geprueftKz + ", " + bestandszaehlungenInfo.datInventur;
                String insertWerte = bestandszaehlungenInfo.idSqlIstIdentity
                        ? "(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                        : "(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

                String speicherSql = "IF EXISTS (SELECT 1 FROM " + bestandszaehlungenInfo.tabellenname + " WHERE " + bestandszaehlungenInfo.blattNr + " = ? AND " + bestandszaehlungenInfo.datum + " = ? AND " + bestandszaehlungenInfo.posNr + " = ?) "
                        + "BEGIN "
                        + "UPDATE " + bestandszaehlungenInfo.tabellenname + " "
                        + "SET " + bestandszaehlungenInfo.mengeLager + " = ?, " + bestandszaehlungenInfo.datInventur + " = ?, " + bestandszaehlungenInfo.geprueftKz + " = ? "
                        + "WHERE " + bestandszaehlungenInfo.blattNr + " = ? AND " + bestandszaehlungenInfo.datum + " = ? AND " + bestandszaehlungenInfo.posNr + " = ? "
                        + "END "
                        + "ELSE "
                        + "BEGIN "
                        + "INSERT INTO " + bestandszaehlungenInfo.tabellenname + " ("
                        + insertSpalten
                        + ") VALUES " + insertWerte + " "
                        + "END";
                String pruefSql = "SELECT TOP 1 " + bestandszaehlungenInfo.mengeLager + " FROM " + bestandszaehlungenInfo.tabellenname + " "
                        + "WHERE " + bestandszaehlungenInfo.blattNr + " = ? AND " + bestandszaehlungenInfo.posNr + " = ? "
                        + "AND " + bestandszaehlungenInfo.identNrBestand + " = ? AND " + bestandszaehlungenInfo.datum + " = ? "
                        + "ORDER BY " + bestandszaehlungenInfo.datum + " DESC";

                try (PreparedStatement quelleStatementNachPosition = connection.prepareStatement(quelleSqlNachPosition);
                     PreparedStatement quelleStatementNachIdent = connection.prepareStatement(quelleSqlNachIdent);
                     PreparedStatement speicherStatement = connection.prepareStatement(speicherSql);
                     PreparedStatement pruefStatement = connection.prepareStatement(pruefSql)) {
                    for (ZaehlmengenSpeicherPositionDto position : anfrage.getPositionen()) {
                        PositionSqlInfo sqlInfo = ladePositionSqlInfo(
                                quelleStatementNachPosition,
                                quelleStatementNachIdent,
                                anfrage.getZaehlerlistenId(),
                                position
                        );
                        Artikel lokalerArtikel = findeLokalenArtikel(lokaleArtikel, position);
                        schreibePositionInSqlServer(
                                speicherStatement,
                                bestandszaehlungenInfo,
                                anfrage.getZaehlerlistenId(),
                                stichtag,
                                position,
                                sqlInfo,
                                lokalerArtikel
                        );
                        pruefeGeschriebenePositionInSqlServer(
                                pruefStatement,
                                bestandszaehlungenInfo,
                                anfrage.getZaehlerlistenId(),
                                stichtag,
                                position,
                                sqlInfo
                        );
                    }
                }

                connection.commit();
            } catch (SQLException | RemoteDatenException exception) {
                try { connection.rollback(); } catch (SQLException rollbackFehler) { exception.addSuppressed(rollbackFehler); }
                throw exception;
            }
        } catch (SQLException | ClassNotFoundException exception) {
            throw new RemoteDatenException("SQL-Server-Speichern der Zaehlmengen fehlgeschlagen.", exception);
        }
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
            int idSqlSpaltenIndex = -1;
            for (int index = 1; index <= metaData.getColumnCount(); index++) {
                String spaltenname = metaData.getColumnName(index);
                spaltennamen.add(spaltenname);
                if (normalisiereSqlNamen(spaltenname).equals(normalisiereSqlNamen("id_sql"))) {
                    idSqlSpaltenIndex = index;
                }
            }
            return new BestandszaehlungenSqlInfo(
                    tabellenname,
                    findeSpaltennamen(spaltennamen, "id_sql", "idsql", "idsqlbestand"),
                    findeSpaltennamen(spaltennamen, "ident_nr_bestand", "identnrbestand", "ident_nr", "identnr"),
                    findeSpaltennamen(spaltennamen, "datum"),
                    findeSpaltennamen(spaltennamen, "lagerort"),
                    findeSpaltennamen(spaltennamen, "menge"),
                    findeSpaltennamen(spaltennamen, "blatt_nr", "blattnr"),
                    findeSpaltennamen(spaltennamen, "pos_nr", "posnr"),
                    findeSpaltennamen(spaltennamen, "menge_lager", "mengelager"),
                    findeSpaltennamen(spaltennamen, "zaehl_nr", "zaehlnr"),
                    findeSpaltennamen(spaltennamen, "hinweis"),
                    findeSpaltennamen(spaltennamen, "gesamt_menge", "gesamtmenge"),
                    findeSpaltennamen(spaltennamen, "menge_offen", "mengeoffen"),
                    findeSpaltennamen(spaltennamen, "bestell_menge", "bestellmenge"),
                    findeSpaltennamen(spaltennamen, "bedarf"),
                    findeSpaltennamen(spaltennamen, "geprueft_kz", "geprueftkz"),
                    findeSpaltennamen(spaltennamen, "dat_inventur", "datinventur"),
                    idSqlSpaltenIndex > 0 && metaData.isAutoIncrement(idSqlSpaltenIndex)
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

    private PositionSqlInfo ladePositionSqlInfo(
            PreparedStatement quelleStatementNachPosition,
            PreparedStatement quelleStatementNachIdent,
            int blattNr,
            ZaehlmengenSpeicherPositionDto position
    ) throws SQLException, RemoteDatenException {
        quelleStatementNachPosition.clearParameters();
        quelleStatementNachPosition.setInt(1, blattNr);
        quelleStatementNachPosition.setInt(2, position.getPositionsNummer());
        try (ResultSet resultSet = quelleStatementNachPosition.executeQuery()) {
            if (!resultSet.next()) {
                return ladePositionSqlInfoPerIdentnummer(
                        quelleStatementNachIdent,
                        blattNr,
                        position
                );
            }

            String identNr = liesText(resultSet, "IdentNr", position.getArtikelnummer());
            if (!identNr.equals(position.getArtikelnummer().trim())) {
                throw new RemoteDatenException("Die Artikelzuordnung auf dem SQL-Server hat sich geaendert. Blatt bitte neu laden.");
            }
            String lagerort = liesText(resultSet, "Lagerort", "0");
            if (lagerort.isEmpty()) {
                lagerort = "0";
            }

            return new PositionSqlInfo(
                    resultSet.getInt("IDSQL"),
                    resultSet.getInt("PosNr"),
                    identNr,
                    lagerort
            );
        }
    }

    private PositionSqlInfo ladePositionSqlInfoPerIdentnummer(
            PreparedStatement quelleStatementNachIdent,
            int blattNr,
            ZaehlmengenSpeicherPositionDto position
    ) throws SQLException, RemoteDatenException {
        String artikelnummer = position.getArtikelnummer() == null ? "" : position.getArtikelnummer().trim();
        if (artikelnummer.isEmpty()) {
            throw new RemoteDatenException("Position " + position.getPositionsNummer() + " fuer Blatt " + blattNr + " wurde auf dem SQL-Server nicht gefunden.");
        }

        quelleStatementNachIdent.clearParameters();
        quelleStatementNachIdent.setInt(1, blattNr);
        quelleStatementNachIdent.setString(2, artikelnummer);
        try (ResultSet resultSet = quelleStatementNachIdent.executeQuery()) {
            if (!resultSet.next()) {
                throw new RemoteDatenException("Position " + position.getPositionsNummer() + " fuer Blatt " + blattNr + " wurde auf dem SQL-Server nicht gefunden.");
            }

            String identNr = liesText(resultSet, "IdentNr", artikelnummer);
            String lagerort = liesText(resultSet, "Lagerort", "0");
            if (lagerort.isEmpty()) {
                lagerort = "0";
            }

            return new PositionSqlInfo(
                    resultSet.getInt("IDSQL"),
                    resultSet.getInt("PosNr"),
                    identNr,
                    lagerort
            );
        }
    }

    private Artikel findeLokalenArtikel(List<Artikel> lokaleArtikel, ZaehlmengenSpeicherPositionDto position) {
        for (Artikel artikel : lokaleArtikel) {
            if (artikel.getPositionsNummer() == position.getPositionsNummer()) {
                return artikel;
            }
        }
        return null;
    }

    private void schreibePositionInSqlServer(
            PreparedStatement speicherStatement,
            BestandszaehlungenSqlInfo bestandszaehlungenInfo,
            int blattNr,
            String stichtag,
            ZaehlmengenSpeicherPositionDto position,
            PositionSqlInfo sqlInfo,
            Artikel lokalerArtikel
    ) throws SQLException {
        int posNr = sqlInfo.positionsNummer > 0 ? sqlInfo.positionsNummer : position.getPositionsNummer();
        int istMenge = lokalerArtikel == null ? 0 : lokalerArtikel.getIstMenge();

        speicherStatement.clearParameters();
        speicherStatement.setInt(1, blattNr);
        speicherStatement.setString(2, stichtag);
        speicherStatement.setInt(3, posNr);
        if (position.getZaehlMenge() == null) {
            speicherStatement.setNull(4, java.sql.Types.INTEGER);
        } else {
            speicherStatement.setInt(4, position.getZaehlMenge());
        }
        speicherStatement.setString(5, stichtag);
        speicherStatement.setString(6, "N");
        speicherStatement.setInt(7, blattNr);
        speicherStatement.setString(8, stichtag);
        speicherStatement.setInt(9, posNr);
        int parameterIndex = 10;
        if (!bestandszaehlungenInfo.idSqlIstIdentity) {
            speicherStatement.setInt(parameterIndex++, sqlInfo.idSql);
        }
        speicherStatement.setString(parameterIndex++, sqlInfo.identNr);
        speicherStatement.setString(parameterIndex++, stichtag);
        speicherStatement.setString(parameterIndex++, sqlInfo.lagerort);
        speicherStatement.setInt(parameterIndex++, istMenge);
        speicherStatement.setInt(parameterIndex++, blattNr);
        speicherStatement.setInt(parameterIndex++, posNr);
        if (position.getZaehlMenge() == null) {
            speicherStatement.setNull(parameterIndex++, java.sql.Types.INTEGER);
        } else {
            speicherStatement.setInt(parameterIndex++, position.getZaehlMenge());
        }
        speicherStatement.setInt(parameterIndex++, posNr);
        speicherStatement.setInt(parameterIndex++, 0);
        speicherStatement.setInt(parameterIndex++, istMenge);
        speicherStatement.setInt(parameterIndex++, 0);
        speicherStatement.setInt(parameterIndex++, 0);
        speicherStatement.setInt(parameterIndex++, 0);
        speicherStatement.setString(parameterIndex++, "N");
        speicherStatement.setString(parameterIndex, stichtag);
        speicherStatement.executeUpdate();
    }

    private void pruefeGeschriebenePositionInSqlServer(
            PreparedStatement pruefStatement,
            BestandszaehlungenSqlInfo bestandszaehlungenInfo,
            int blattNr,
            String stichtag,
            ZaehlmengenSpeicherPositionDto position,
            PositionSqlInfo sqlInfo
    ) throws SQLException, RemoteDatenException {
        int posNr = sqlInfo.positionsNummer > 0 ? sqlInfo.positionsNummer : position.getPositionsNummer();
        Integer erwarteteMenge = position.getZaehlMenge();

        pruefStatement.clearParameters();
        pruefStatement.setInt(1, blattNr);
        pruefStatement.setInt(2, posNr);
        pruefStatement.setString(3, sqlInfo.identNr);
        pruefStatement.setString(4, stichtag);

        try (ResultSet resultSet = pruefStatement.executeQuery()) {
            if (!resultSet.next()) {
                throw new RemoteDatenException(
                        "SQL-Write-Check fehlgeschlagen. Nach dem Speichern wurde kein Datensatz gefunden fuer Blatt "
                                + blattNr + ", Pos " + posNr + ", Ident " + sqlInfo.identNr + ", Datum " + stichtag + "."
                );
            }

            Integer geleseneMenge = resultSet.getObject(1) == null
                    ? null
                    : Math.round(resultSet.getFloat(1));
            aktualisiereSchreibDiagnose(bestandszaehlungenInfo, blattNr, posNr, sqlInfo.identNr, stichtag, erwarteteMenge, geleseneMenge);

            if ((erwarteteMenge == null && geleseneMenge != null)
                    || (erwarteteMenge != null && !erwarteteMenge.equals(geleseneMenge))) {
                throw new RemoteDatenException(
                        "SQL-Write-Check fehlgeschlagen. Blatt " + blattNr
                                + ", Pos " + posNr
                                + ", Ident " + sqlInfo.identNr
                                + ", Datum " + stichtag
                                + ": erwartet " + (erwarteteMenge == null ? "NULL" : erwarteteMenge)
                                + ", gelesen " + (geleseneMenge == null ? "NULL" : geleseneMenge) + "."
                );
            }
        }
    }

    private void aktualisiereSchreibDiagnose(
            BestandszaehlungenSqlInfo bestandszaehlungenInfo,
            int blattNr,
            int posNr,
            String identNr,
            String stichtag,
            Integer erwarteteMenge,
            Integer geleseneMenge
    ) {
        if (!istDiagnoseZiel(blattNr, identNr)) {
            return;
        }
        letzteDiagnose = "SQL-Schreibdiagnose\n"
                + "Tabelle: " + bestandszaehlungenInfo.tabellenname + "\n"
                + "Blatt: " + blattNr + "\n"
                + "Pos: " + posNr + "\n"
                + "Ident: " + identNr + "\n"
                + "Stichtag: " + stichtag + "\n"
                + "Erwartete Menge: " + formatiereDiagnoseWert(erwarteteMenge) + "\n"
                + "Gelesene SQL-Menge: " + formatiereDiagnoseWert(geleseneMenge);
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

    private boolean istDiagnoseZiel(int blattNr, String identNr) {
        return blattNr == DIAGNOSE_BLATT_NR
                && identNr != null
                && DIAGNOSE_IDENT_NR.equalsIgnoreCase(identNr.trim());
    }

    private String formatiereDiagnoseWert(Integer wert) {
        return wert == null ? "NULL" : String.valueOf(wert);
    }

    private static final class PositionSqlInfo {
        private final int idSql;
        private final int positionsNummer;
        private final String identNr;
        private final String lagerort;

        private PositionSqlInfo(int idSql, int positionsNummer, String identNr, String lagerort) {
            this.idSql = idSql;
            this.positionsNummer = positionsNummer;
            this.identNr = identNr == null ? "" : identNr;
            this.lagerort = lagerort == null ? "0" : lagerort;
        }
    }

    private static final class BestandszaehlungenSqlInfo {
        private final String tabellenname;
        private final String idSql;
        private final String identNrBestand;
        private final String datum;
        private final String lagerort;
        private final String menge;
        private final String blattNr;
        private final String posNr;
        private final String mengeLager;
        private final String zaehlNr;
        private final String hinweis;
        private final String gesamtMenge;
        private final String mengeOffen;
        private final String bestellMenge;
        private final String bedarf;
        private final String geprueftKz;
        private final String datInventur;
        private final boolean idSqlIstIdentity;

        private BestandszaehlungenSqlInfo(
                String tabellenname,
                String idSql,
                String identNrBestand,
                String datum,
                String lagerort,
                String menge,
                String blattNr,
                String posNr,
                String mengeLager,
                String zaehlNr,
                String hinweis,
                String gesamtMenge,
                String mengeOffen,
                String bestellMenge,
                String bedarf,
                String geprueftKz,
                String datInventur,
                boolean idSqlIstIdentity
        ) {
            this.tabellenname = tabellenname;
            this.idSql = idSql;
            this.identNrBestand = identNrBestand;
            this.datum = datum;
            this.lagerort = lagerort;
            this.menge = menge;
            this.blattNr = blattNr;
            this.posNr = posNr;
            this.mengeLager = mengeLager;
            this.zaehlNr = zaehlNr;
            this.hinweis = hinweis;
            this.gesamtMenge = gesamtMenge;
            this.mengeOffen = mengeOffen;
            this.bestellMenge = bestellMenge;
            this.bedarf = bedarf;
            this.geprueftKz = geprueftKz;
            this.datInventur = datInventur;
            this.idSqlIstIdentity = idSqlIstIdentity;
        }
    }
}
