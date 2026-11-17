# Inventur-App

Die Android-App und das Spring-Boot-Backend sind getrennte Projekte.
Die Android-App verwendet derzeit JDBC fuer SQL Server und SQLite als lokalen
Offline-Speicher. Das Backend verwendet PostgreSQL; eine REST-Anbindung der
Android-App ist noch nicht implementiert.

## Projektstruktur

`app/src/main` enthaelt die aktive Android-App mit SQLite und direktem JDBC.
`ArtikeluebersichtActivity` traegt das gemeinsame Laden, lokale Speichern,
Navigieren und die Hauptmenuepruefung beider Artikelansichten.
`ArtikeluebersichtV2Activity` ergaenzt Layout, Kartenadapter und Detaildialog.
Die Manifest-Einstiegspunkte und vorhandenen Datenbanknamen bleiben erhalten.

`app/src/test` prueft Java-Logik mit Fakes; `app/src/androidTest` prueft Android
und isolierte SQLite-Dateien. `app/src/sqlAdapterTest` wird ausschliesslich in
Testartefakte eingebunden. Der dortige SQL-Server-Journaladapter gehoert nicht
zum aktiven internen Mengenweg. `backend` ist ein eigenstaendiges Maven-Projekt;
`sql` enthaelt manuelle SQL-Artefakte.

Der manuelle Mengenweg ist `SqlMengenabgleichAnzeige` -> `ArtikelRepository` ->
`EinzelpositionsSynchronisierung` -> `SqlServerMengenSchreibversuch` mit lokaler
Quittierung durch `LokalerInventurSpeicher`. Die alte pauschale SQL-Uebertragung
endet direkt an der Schreibsperre; ihre fruehere INSERT-/UPDATE-Implementierung
ist entfernt. Es gibt keinen zweiten UI-Schreibweg ohne Mengenquittierung.

## Architektur und Grenzen

Beim bewussten Verlassen beider Artikelansichten verwenden Toolbar-Zurueck,
System-Zurueck und der Wechsel zu einem anderen Blatt denselben Austrittsablauf.
Ausstehende lokale Speicherung wird einschliesslich neuerer Eingaben beendet;
danach ruft `ArtikelRepository.synchronisiereBeimVerlassen` den vorhandenen
Mengenabgleich auf. Eine Fortschrittsanzeige verhindert parallele Austrittsstarts.
Activity-Pause und Neuerstellung allein starten keine SQL-Uebertragung.
Beim bewussten Austritt zeigt derselbe Ablauf eine Bestaetigung mit angenommenen
Mengen und der Anzahl offener Positionen vor diesem Vorgang. Frueher bestaetigte
Positionen werden nicht erneut gezaehlt; gleiche Serverwerte werden gesondert
als jetzt bestaetigt ausgewiesen. Ohne Mengenauftrag erscheint keine Schreibbehauptung.
Erst OK erlaubt bei vollstaendigem Erfolg die vorgesehene Navigation. Bei Fehlern
bleibt die Liste offen; kurze Gruende je Position und ein auffaelliger !-Button
trennen Benutzerhinweise von technischen Details. Unklare Versuche werden nicht
blind wiederholt. Ein Verbindungsfehler vor dem Schreiben erlaubt einen erneuten Versuch.


Automatische Namensvorgaben der Artikelansicht sind nur Anzeigehilfen. Sie
werden beim Mengen- oder Notizspeichern nicht als neue Kopfdaten uebernommen.
Zaehler und Schreiber sind in dieser Phase nicht bearbeitbar. Ein unveraenderter
Abschlussstatus erzeugt weder neue Revision noch Warteschlangeneintrag.
Vorhandene historische Kennzeichen werden dadurch nicht geloescht oder quittiert.

Die Abschlussentscheidung ist unabhaengig: Auch eine vollstaendig gezaehlte Liste
kann offen bleiben. Nur `Abschliessen` merkt den Abschluss lokal vor.
Navigation und Hauptmenue pruefen ausschliesslich offene Mengenkennzeichen und
nicht bestaetigte Einzelmengenauftraege. Alte Listenauftraege sowie Notiz-, Kopf-
und Abschlusskennzeichen allein sperren nicht. Diese Daten und Kennzeichen bleiben
lokal erhalten und gelten dadurch nicht als serverseitig bestaetigt.
Die Austrittspruefung gilt fuer das betroffene Blatt, die zentrale Hauptmenuepruefung
fuer den gesamten lokalen Mengenbestand. Bei Lesefehlern bleibt die Navigation gesperrt.
Ein erneuter Versuch nutzt denselben Mengenweg und ueberspringt quittierte Mengen.
Unklare Commits werden nicht blind wiederholt. Im grundsaetzlich gesperrten
SQL-Modus wird kein wirkungsloser Uebertragungsversuch angeboten.

Die Listenuebersicht liest SQL Server -> JDBC -> Anzeigestand im Arbeitsspeicher.
Sie wird beim Oeffnen und erneuten Anzeigen sowie bei manueller Aktualisierung,
Netzwiederkehr und der bestehenden Vordergrundpruefung erneut geladen.
Ladeanzeige und Abrufzeit beruhen auf der vollstaendigen Listenantwort, nicht
auf einem Verbindungscheck. Die letzte Erfolgszeit gilt fuer die App-Sitzung.
Bei Fehlern bleiben lokal gespeicherte Listen verfuegbar; nur lokal vorhandene
Blaetter bleiben auch neben einem erfolgreichen Serverstand sichtbar.

Fuer Artikel bleibt der Ladeweg SQL Server -> JDBC -> SQLite -> Oberflaeche.
`ApiInventurRemoteDataSource` ist trotz ihres Namens eine JDBC-Datenquelle.
Beim ersten erfolgreichen Laden werden Listen und Artikel lokal gespeichert.
Ein erneutes Oeffnen eines Blatts aktiviert keinen wiederholten Artikelimport.
Vorhandene Positionen werden durch die Initialisierung nicht ersetzt. Fuer
neue Positionen bleiben SQL-Positions-ID und Blattnummer im lesenden Transport
bis zur lokalen Speicherung erhalten. Der erste Download ergaenzt bei genau
einer passenden Ergebniszeile auch die getrennte SQL-Ergebnis-ID, den SQL-Stichtag
und den gelesenen Ausgangswert. Fehlende oder mehrdeutige Ergebnisse erteilen
keine Schreibfreigabe. Beim Mengenvergleich koennen fehlende technische
Identitaeten geaenderter Altpositionen nach eindeutigem SQL-Abgleich ergaenzt
werden; die fachliche Durchgangsfreigabe bleibt eine eigene Benutzerentscheidung.
Das aktiviert keinen automatischen Artikelabgleich. Positionsnummer
und Artikelnummer sind kein Ersatz fuer die SQL-Identitaet.
Die ID einer Bestandszaehlung ist nicht die ID der Artikelposition.
Ohne Serververbindung bleibt der vorhandene lokale Bestand nutzbar.

Die Artikelansichten enthalten keine manuellen Synchronisierungs-Testbuttons und
kein SQL-Positionsstatusfeld. Der nur fuer diese Anzeige gestartete Zusatzabruf
entfaellt. Der vorhandene lesende Positionsvergleich bleibt getrennt nutzbar;
die eindeutige Identitaetspruefung des automatischen Mengenabgleichs bleibt erhalten.

Die Rueckkehr aus der Blattauswahl ins Hauptmenue bleibt bei offenen Mengenauftraegen
oder Mengenkennzeichen gesperrt, auch bei aktiver SQL-Schreibsperre.
Die Pruefung umfasst den gesamten lokalen Bestand. Positive Revisionsnummern
allein sperren nicht: Sie koennen historische Aenderungen beschreiben. Die
Diagnose zeigt Auftraege, geaenderte Positionen und Koepfe, ungeklaerte
Altpositionen, Revisionen und gespeicherte Serverstaende getrennt fuer das
geoeffnete Blatt und den Gesamtbestand. Ein gespeicherter Serverstand allein
ist keine revisionsbezogene Quittierung. Die Diagnose schreibt keine Daten.
Im gesperrten SQL-Modus gibt es keine Schaltflaeche zum erneuten Synchronisieren.
Der einfache SQL-Mengenabgleich verwendet die Geschaeftsregel eines Tablets je
Liste. Er verlangt eine eindeutige Ergebnis-/Durchgangszuordnung und einen
geprueften erwarteten Serverwert. Notizen, Kopf und Abschluss besitzen weiterhin
keinen SQL-Speichervertrag. Ohne Serverjournal gibt es keine automatische
Wiederholung eines unklar abgeschlossenen SQL-Versuchs.

Ein vorbereiteter Einzelmengenablauf ist separat gegen Fakes und einen isolierten
SQLite-Testserver implementiert. `EinzelpositionsSynchronisierung` verlangt eine
explizite Blatt-, Positions-, Artikel-, Ergebnis- und Durchgangsidentitaet sowie
einen bestaetigten Ausgangsstand mit Vergleichstoken. Der Durchgang wird nicht
aus einem Datum geraten. Fehlende Ergebniszeilen werden nicht angelegt.
Der direkte JDBC-Mengenweg nutzt denselben Ablauf mit einmaligem Versand und
revisionsbezogener lokaler Quittierung. Der gesonderte Journal-Testserver
liegt ausschliesslich unter androidTest und besitzt keine Firmenverbindung.

Die ausdruecklich aktivierte Variante `internalSql` synchronisiert geaenderte
Mengen beim bewussten Verlassen beider Artikelansichten. Ein nur auf SQL
vorhandenes Blatt kann geoeffnet und erstmalig vollstaendig nach SQLite geladen
werden. Kopf und Artikel werden zusammen gespeichert. Bereits vorhandene
Positionen, ihre Mengen und Notizen werden nicht ersetzt.
Mengen werden zuerst lokal gespeichert und nur bei einer echten Aenderung
markiert. Notizen haben ein eigenes Kennzeichen. Die Synchronisierung liest
nur geaenderte Mengen der aktuellen Liste und meldet unklare Positionen einzeln.
Die frische eindeutige SQL-Zuordnung aus dem Download braucht keine zusaetzliche
manuelle Durchgangsfreigabe. Historische ungeklaerte Positionen werden damit
nicht automatisch umgedeutet.
`ApiInventurRemoteDataSource.kontrollierterMengenversuch()` verwendet die
bestehende Verbindung; `SqlServerMengenSchreibversuch.schreibeEinmal()` prueft
Identitaet und erwarteten Wert in einer serialisierbaren Transaktion und
aendert ausschliesslich MengeLager anhand der Ergebnis-ID und alten Menge.
Er prueft den gelesenen Stand vor und nach Commit. Die Fehleranzeige nennt
SQL-Code und SQL-Status ohne rohe Treibermeldungen.
Ein unklarer Commit wird nicht wiederholt. Ein vor dem Netzaufruf dauerhaft
gesicherter lokaler Versuchsstatus verhindert erneutes Senden derselben
Position durch die Oberflaeche, auch nach Neustart. Es gibt kein automatisches
Zuruecksetzen dieser Sperre; weitere Versuche verlangen manuelle Pruefung.
Nach eindeutigem Commit und Ruecklesen quittiert der gemeinsame
`EinzelpositionsSynchronisierung`-Ablauf die passende Mengenrevision in SQLite.
Vor der Quittierung prueft eine neue JDBC-Verbindung die gespeicherte Menge und
Ergebnisidentitaet. Scheitert diese Pruefung, bleibt der Auftrag offen und wird
nicht automatisch wiederholt. Ungeklaerte Durchgaenge bleiben bis zur fachlichen
Pruefung offen. Andere fehlerhafte Positionen verhindern die Verarbeitung
eindeutig freigegebener Mengen nicht.
Die lokale Menge wird dabei nicht ersetzt. Neuere Revisionen sowie Notizen,
Kopf und Abschluss bleiben offen. Eine reine Uebereinstimmung nach einem
frueheren unklaren Versuch erzeugt keine nachtraegliche Quittierung.
Die gemeinsame Rueckmeldung beim Verlassen bleibt fuer Einzel- und Mehrmengen
identisch. Fehlerhafte Positionen werden gesondert aufgefuehrt.
Jede freigegebene Position verwendet denselben
Einzelablauf und eine eigene SQL-Transaktion. Unveraenderte Mengen erzeugen
kein UPDATE. Notiz-/Kopf-/Abschlussaenderungen werden nicht mitquittiert.
Die alte pauschale Listen-Schreiblogik bleibt gesperrt. Andere Build-Varianten
haben keine Freigabe fuer diesen internen SQL-Mengenweg.

SQLite-Version 12 ergaenzt ein dauerhaftes Einzelauftragsjournal. Ein Auftrag
enthaelt eine feste Kennung, Nutzdaten und die uebertragene lokale Revision.
Der isolierte Journaladapter fragt vor Wiederholungen die dauerhafte
Serverquittung ab. Der einfache JDBC-Weg verwendet ausschliesslich das bereits
vorhandene lokale Journal: Vor dem Netzaufruf wird der Versuch als unklar
gesichert; nur ein noch nicht versuchter Auftrag darf gesendet werden. Ein
App-Neustart hebt diese Sperre nicht auf. Neue Serverobjekte oder Journale
werden fuer den einfachen Weg nicht angelegt. Die lokale Quittierung uebernimmt nur den
bestaetigten Mengenstand und loescht das Mengenkennzeichen nur bei identischer
Revision und Menge. Neuere Aenderungen, Notizen und Kopf-/Abschlusskennzeichen
bleiben erhalten. Ein Listenauftrag entfaellt erst, wenn keine dieser offenen
Aenderungen und keine offenen Einzelauftraege mehr vorhanden sind.

Die vorhandene Ergebniszuordnung aus SQLite-Version 13 wird beim neuen
Download verwendet. Sie speichert Ergebnis-ID, Positions-ID, SQL-Datum und
Zaehlnummer getrennt. Der Download prueft Blatt, Position, Artikel und Stichtag
und speichert den gelesenen Serverstand nur fuer neue, eindeutige Zeilen.
Eine nachtraegliche Ergaenzung bestehender Altpositionen findet nicht statt.
Die frueheren Dialoge fuer Identitaetszuordnung und SQL-Einzelversuch sind aus
dem aktiven Ablauf entfernt; deren Hilfsklassen bleiben fuer bestehende Tests
erhalten. Ein spaeterer Stichtagswechsel oder eine unerwartete SQL-Aenderung
erteilt keine neue Freigabe und quittiert keine lokale Aenderung.
Alte lokale id_sql- und zaehl_nr-Werte aus dem Positionsseed sind kein Nachweis
einer SQL-Ergebnis-ID oder einer echten Zaehlnummer.

Ein weiterer JDBC-Adapter fuer das gesonderte Serverjournal liegt ausschliesslich in `app/src/sqlAdapterTest/java`.
Gradle nimmt ihn nur in Unit- und Instrumentierungstests auf; weder die normale
noch die interne Firmen-App enthaelt ihn. Er verwendet die bestehende
Einzelpositions-Schnittstelle und keine Firmenkonfiguration. Vor jedem RPC
verlangt er den festen Testkatalog `InventurEinzelSync_Isoliert`, dessen
Testmarker und eine Verbindung ohne aeussere Transaktion. Ergebnis-ID und
Positions-ID werden getrennt gebunden. Ein gelesener Ausgangsstand enthaelt
die Ergebnis-ID bereits im lokalen Serverstand und im Einzeljournal;
eine weitere SQLite-Migration oder nachtraegliche Altbestandszuordnung erfolgt nicht.

Der lokale SQL-Vorschlag steht in `.cache/sqlserver-einzeltest/isoliert.sql`.
Er bleibt absichtlich durch Git ignoriert. Er erzeugt ausschliesslich in einer
vorher angelegten leeren isolierten Testdatenbank eine synthetische
`Bestandszaehlungen`, eine explizite Ergebnis-/Positions-/Durchgangszuordnung,
ein Auftragsjournal sowie Lese- und Verarbeitungsprozeduren. Kein Adapter legt
Ergebniszeilen an. Es gibt keine automatische Installation oder Migration.
Das Skript ist fuer SQLCMD-Modus mit Fehlerabbruch und SQL Server ab 2016 SP1
bestimmt; jeder DDL-Abschnitt und jede Prozedur prueft den Testkatalog.
Die verwendete CREATE-OR-ALTER-Syntax ist in der
[Microsoft-Dokumentation](https://learn.microsoft.com/en-us/sql/t-sql/statements/create-procedure-transact-sql?view=sql-server-ver16) beschrieben.
Die ebenfalls ignorierte `pruefung.sql` enthaelt einen manuellen Prueflauf mit
synthetischen Fixtures fuer Nullmenge, NULL, Wiederholung, Inhaltswechsel,
Konflikt, fehlende Identitaet und Journalrollback. Der vollstaendige Prueflauf
wurde auf dem lokalen SQL Server erfolgreich ausgefuehrt. Bestehende
Testdaten werden nicht zurueckgesetzt.

Der Vergleichstoken kombiniert die rowversion von Ergebnis und Zuordnung.
Die Verarbeitungsprozedur serialisiert die Auftragskennung mit UPDLOCK/HOLDLOCK
und fuehrt Mengenvergleich, Mengenupdate, Ruecklesepruefung und
Journalinsert in einer Transaktion aus. Identische Wiederholungen lesen
dieselbe gespeicherte Antwort; veraenderte Inhalte derselben Kennung werden
abgewiesen. Nur MengeLager wird fachlich geaendert. Nicht ganzzahlige oder
nicht als Integer darstellbare SQL-Mengen werden vom Adapter abgewiesen.

Ein separater lokaler Docker-Container verwendet SQL Server 2022 Developer
mit synthetischen Daten. Der vorhandene JDBC-Adapter besteht dort 20 echte
Integrationstests: Mengen einschliesslich 0/NULL, Quittungen, Wiederholungen,
Inhaltswechsel, konkurrierende Verbindungen, gehaltene Ergebnissperren,
Zuordnungswechsel, rowversion und Trigger-/Journalrollback. Der eingeschraenkte
Testclient besitzt nur die vorgesehenen Prozedurrechte und Zugriff auf den
Umgebungsmarker. rowversion wird vor der Hexdarstellung explizit nach binary(8)
konvertiert; die SQL-Kennungspruefung erlaubt Bindestriche korrekt.
Antwortverlust nach einem echten Commit wird durch Schliessen der JDBC-Verbindung
vor Auslieferung der Antwort simuliert. Die bestehende Synchronisierung findet
danach die Quittung ohne zweiten Schreibvorgang. Physischer Netzverlust waehrend
des Commits, Prozessabsturz und Deadlock-Wiederanlauf sind noch nicht verifiziert.
Lokale Einrichtung, verschluesselte Zugangsdaten, SQL-Skripte und Testbericht
bleiben unter `.cache/sqlserver-einzeltest/` ausserhalb von Git.
Eine Android-Testvariante benoetigt eine separate Testverbindungsquelle,
explizite Ergebnis-/Durchgangszuordnung und einen vor der lokalen Aenderung
gelesenen Ausgangsstand. Die normale App wird dadurch nicht freigeschaltet.
Generische Altbestaende gelten nicht als Ausgangsstand. Die Uebernahme in die
Firmen-App und ein Firmen-Schreibtest benoetigen eine gesonderte Freigabe.

`SqlServerSchreibschutz` blockiert weiterhin die alten SQL-Schreibwege der App.
Die lokalen Zaehlergebnisse bleiben erhalten. Nur eindeutig bestaetigte
Mengenrevisionen werden quittiert; andere offene Aufgaben blockieren weiterhin
das Hauptmenue. Der interne Mengenweg hat eine eigene Build-Freigabe.
Die zusaetzliche JDBC- und SQL-Textpruefung ersetzt kein serverseitig beschraenktes
Lesekonto. Listenbelegung und Mehrgeraetesperren sind nicht implementiert.
Der Vergleich prueft erwarteten Wert und Zuordnung, besitzt aber keinen
rowversion-Nachweis zwischenzeitlicher Aenderungen mit Rueckkehr zum alten Wert.

SQL-Testdaten und die lokale Demo verwenden getrennte SQLite-Dateien.
Ein Wechsel der Build-Variante setzt den lokalen Bestand nicht zurueck.
SQLite-Versionserhoehungen koennen additive Migrationen ausloesen; Version 11
ergaenzt lokale Aenderungskennzeichen und Revisionen, Version 12 das Einzelauftragsjournal. Ungeklaerte Altbestaende
bleiben geschuetzt. Android-Backup und Geraeteuebertragung sind deaktiviert;
ein bewusster lokaler Export ist noch nicht implementiert.

## Voraussetzungen

- Android Studio mit Android SDK 36.1 und JDK 21; Android-Minimum API 24.
- Das Java-Sprachniveau der Android-Quellen ist 11.
- Git und Internetzugriff fuer noch nicht vorhandene Build-Abhaengigkeiten.
- Fuer Backend-Integrationstests zusaetzlich Docker mit lokaler Linux-Engine.

Gradle Wrapper und Maven Wrapper liegen bei. Android Studio kann den lokalen
SDK-Pfad in `local.properties` hinterlegen; alternativ ist `ANDROID_HOME` moeglich.
Lokale Konfiguration und Build-Ausgaben gehoeren nicht in Git.

## Oeffentliche Android-Builds

Debug und Release enthalten grundsaetzlich keine SQL-Verbindungswerte aus der
lokalen Konfiguration, auch wenn diese auf dem Build-Rechner vorhanden ist.
Builds ohne SQL-Konfiguration sind moeglich. Ohne konfigurierte Verbindung zeigt
die App vorhandene lokale Daten; auf einer neuen Installation fehlt ein
Serverbestand. Es erfolgt kein automatischer Wechsel in den Mock-Demo-Modus.

```powershell
$env:JAVA_HOME = "$env:ProgramFiles\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

Das Release-APK ist unsigniert. Private Signierschluessel bleiben lokal.
APK-, AAB-, BuildConfig- und sonstige generierte Build-Dateien werden ignoriert.
Vor einer Weitergabe sind das konkrete Artefakt und sein Inhalt zu pruefen;
Ignore-Regeln verhindern keine manuellen Uploads zu Releases oder CI-Artefakten.

## Lokaler JDBC-Testbuild

Ein separater Build `internalSql` ist nur nach ausdruecklicher Aktivierung
verfuegbar. Ausschliesslich dieser Build liest SQL-Werte aus der ignorierten
`local.properties`: `sqlServerHost`, `sqlServerPort`, `sqlServerDatabase`,
`sqlServerSchema`, `sqlServerUsername` und `sqlServerPassword`.
Verbindungswerte werden nicht in Beispieldateien eingecheckt.

```powershell
.\gradlew.bat -PenableInternalSqlBuild=true :app:assembleInternalSql
```

Das Artefakt dieser Variante ist ausschliesslich fuer den lokalen Testbetrieb
bestimmt: Seine Zugangsdaten sind im APK auslesbar. Es darf nicht oeffentlich
weitergegeben oder als Release-Artefakt hochgeladen werden. Der SQL-Schreibschutz
bleibt auch in dieser Variante aktiv.

Die interne Variante behaelt Application-ID, Debug-Signierung und SQLite-Dateien
bei. Ein Update setzt passende Signierung voraus und darf keine App-Daten loeschen.
Oeffentliche Debug- oder Release-Builds sind kein Ersatz fuer eine konfigurierte
interne Testinstallation.

Die interne Variante heisst auf dem Geraet `Firm app`. Zum lokalen Update dient
`./gradlew.bat -PenableInternalSqlBuild=true :app:installInternalSql`.
`installDebug` installiert eine Variante ohne SQL-Verbindungswerte und ist
daher kein Update fuer den laufenden SQL-Testbetrieb.

## Vorbereitung einer Veroeffentlichung

Allgemeiner Anwendungscode, Tests mit fiktiven Daten und die Beschreibung des
lokalen Profils koennen getrennt von Firmenkonfigurationen versioniert werden.
Lokale SQL-Skripte, Datenbankdateien, Diagnoseexporte, Zugangskonfigurationen und
erzeugte APKs bleiben ausgeschlossen. Die gezielten Ignore-Regeln schuetzen vor
versehentlichem `git add .`; sie schuetzen weder bereits versionierte Inhalte
noch ein APK vor dem Auslesen seiner eingebetteten Werte.

Vor einem spaeteren Commit die konkrete Dateiauswahl, den vorgemerkten Diff und
die Git-Historie auf Geheimnisse und vertrauliche Daten pruefen. SQL-Tabellen-
und Spaltennamen im JDBC-Adapter sind technische Schnittstellen und keine
Zugangskonfiguration. Ob diese Schnittstellen und vorhandene Bildressourcen
oeffentlich freigegeben sind, muss vor einer Veroeffentlichung geklaert sein.

Zur Weitergabe ausschliesslich ein geprueftes Debug- oder Release-Artefakt ohne
SQL-Konfiguration verwenden. Keine pauschalen APK-Verzeichnis-Uploads und keine
internen Test-APKs in Releases oder CI-Artefakte aufnehmen. Auch ein oeffentliches
APK darf nicht zusammen mit einem Export lokaler Firmendaten verteilt werden.

Der aktuelle Android-Code hat keinen REST-Uebertragungspfad. Eine spaetere
REST-Anbindung darf den Firmen-SQL-Cache oder seine Warteschlange nicht
automatisch uebernehmen. Dafuer sind eine getrennte Datenquelle und ein eigener
Speicher sowie eine ausdruecklich festgelegte Datenuebernahme erforderlich.

## Android-Tests

Lokale Unit-Tests laufen ohne SQL-Verbindung. Instrumentierungstests mit Fakes
und isolierten SQLite-Datenbanken werden getrennt von SQL-Livetests ausgefuehrt.
Tests duerfen keine Firmen-Zugangsdaten voraussetzen oder SQL-Schreiben gegen
Betriebsdatenbanken ausloesen. Vorhandene Instrumentierungstests haben noch
bekannte Probleme bei Testdatenbank-Kontext und Activity-Lebenszyklus; die
vollstaendige Suite ist nicht durchgehend erfolgreich.

Weitere Backend-Vertraege stehen in [backend/README.md](backend/README.md).

## Neue lokale PostgreSQL-Entwicklungsumgebung unter Windows

Docker Desktop selbst starten. Der Helfer arbeitet nur mit einer lokalen
Linux-Engine. Er loescht oder ersetzt niemals vorhandene Container, Volumes
oder lokale Passwortdateien.

```powershell
docker pull postgres:17-alpine
python backend\scripts\provision-dev.py --check
# Nur auf einer neuen Umgebung ohne bestehende Zielressourcen:
python backend\scripts\provision-dev.py --create
```

Konfiguration der neu erzeugten Demo-Datenbank:

- Container: `inventur-postgres-dev`; Image: `postgres:17-alpine`.
- Datenbank/Benutzer: `inventur_dev` (fiktive Entwicklungsbezeichnungen).
- Bindung: ausschliesslich `127.0.0.1:5433 -> 5432`.
- Persistentes Volume: `inventur-postgres-data`.
- Neues zufaelliges Passwort; keine Ausgabe und keine Klartextdatei auf dem Host.
- Geheimnisuebergabe per Speicherstream in PostgreSQL-eigene Dateien im Container.
- Wiederverwendung durch den aktuellen Windows-Benutzer ueber die lokal
  erzeugte `backend/.cache/inventur-postgres-dev-password.dpapi`.
- Healthcheck und SELECT 1 pruefen nur die neue Datenbank; keine fachlichen Tabellen.

Wenn der Port oder eine Zielressource schon existiert, stoppt `--create`.
Nicht eigenstaendig Ressourcen loeschen, neue Ports waehlen oder fremde Projekte
aendern. Vorhandene Inventur-Ressourcen nur nach eindeutiger Zuordnung weiter nutzen.

DPAPI verwendet CurrentUser. Die Datei ist kein portables Passwortformat und
darf nicht in Git gelangen. Auf einem anderen Rechner neu initialisieren;
weder den alten Truststore noch alte Geheimnisse blind kopieren. Bei Proxy-/CA-
Problemen die Vertrauenskette separat pruefen. Der vorhandene Truststore ist
lokal optional, nicht Teil des Projekts.

Auf anderen Betriebssystemen stehen Maven und das Backend grundsaetzlich bereit;
die Windows-DPAPI-Skripte sind dort nicht nutzbar. Eine dortige sichere
Geheimnisinitialisierung muss separat eingerichtet werden.

## Backend starten und testen

PowerShell im Projektroot:

```powershell
$env:JAVA_HOME = "$env:ProgramFiles\Android\Android Studio\jbr"
cd backend
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-dev.ps1
```

Das Skript liest das lokale DPAPI-Geheimnis in Prozess-Umgebungsvariablen,
gibt es nicht aus und stellt vorherige Werte beim Beenden wieder her.
Flyway wendet Migrationen regulaer an; Hibernate validiert das Schema.
Keine bestehenden Migrationen veraendern. Server mit Strg+C beenden.

Health im Browser: `http://localhost:8080/api/v1/health`.
Alternativ: `Invoke-RestMethod http://localhost:8080/api/v1/health`.

Tests und Build in einem zweiten Terminal im Ordner `backend`:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress test
.\mvnw.cmd --batch-mode --no-transfer-progress package
```

Docker muss fuer die Integrationstests laufen. Sie verwenden isolierte
Testcontainers und erzeugen eigene Zufallsgeheimnisse; niemals eine echte
Betriebsdatenbank als Testziel konfigurieren. `package` fuehrt ebenfalls die
vollstaendige Testsuite aus. Nicht mit skipTests einen Erfolg vortaeuschen.

Demo-Daten fuer die Entwicklungsdatenbank nur bewusst und separat anlegen:
`powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\seed-dev.ps1`.
Die Tests benoetigen diesen Seed nicht.

## JWT/OIDC und externe Konfiguration

Health ist oeffentlich. Fachliche Endpunkte bleiben ohne vertrauenswuerdige
JWT-Konfiguration geschlossen. Es gibt keinen automatisch angelegten Demo-Login.

Diese drei Konfigurationswerte gehoeren zu einem echten externen OIDC-Anbieter:

- `INVENTUR_SECURITY_JWT_ISSUER`
- `INVENTUR_SECURITY_JWT_AUDIENCE`
- `INVENTUR_SECURITY_JWT_JWK_SET_URI`

Issuer/JWKS muessen den vorhandenen HTTPS-Regeln entsprechen. Der Anbieter muss
passende signierte RS256-Access-Tokens ausstellen. Eine aktive lokale
Issuer/Subject-Zuordnung und passende Rolle/Zuweisung sind ebenfalls erforderlich.
Nicht einfach Client-Rollen oder Benutzer-UUIDs als vertrauenswuerdig behandeln.

Bei direktem Maven-Start statt des Windows-Skripts werden Datenbankdaten ausschliesslich
extern als `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` und
`SPRING_DATASOURCE_PASSWORD` bereitgestellt. Keine echten Werte in README,
Befehlsprotokolle oder Projektdateien schreiben. Eine .env-Datei wird nicht
automatisch von Spring Boot geladen.
