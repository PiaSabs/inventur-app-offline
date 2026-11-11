# Inventur-App

Die Android-App und das Spring-Boot-Backend sind getrennte Projekte.
Die Android-App verwendet derzeit JDBC fuer SQL Server und SQLite als lokalen
Offline-Speicher. Das Backend verwendet PostgreSQL; eine REST-Anbindung der
Android-App ist noch nicht implementiert.

## Architektur und Grenzen

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
bis zur lokalen Speicherung erhalten. Altbestaende werden nicht nachtraeglich
zugeordnet. Das aktiviert keinen automatischen Artikelabgleich. Positionsnummer
und Artikelnummer sind kein Ersatz fuer die SQL-Identitaet.
Die ID einer Bestandszaehlung ist nicht die ID der Artikelposition.
Ohne Serververbindung bleibt der vorhandene lokale Bestand nutzbar.

`SqlServerSchreibschutz` blockiert die bekannten SQL-Schreibwege der App.
Mengen, Notizen und Abschluesse bleiben lokal, offene Uebertragungen bleiben
bestehen. Es gibt keine Einstellung zum Freigeben von SQL-Schreiben.
Die zusaetzliche JDBC- und SQL-Textpruefung ersetzt kein serverseitig beschraenktes
Lesekonto. Eine verlaessliche Konflikterkennung zwischen Geraeten fehlt noch.

SQL-Testdaten und die lokale Demo verwenden getrennte SQLite-Dateien.
Ein Wechsel der Build-Variante setzt den lokalen Bestand nicht zurueck.
SQLite-Versionserhoehungen koennen additive Migrationen ausloesen; Version 11
ergaenzt lokale Aenderungskennzeichen und Revisionen. Ungeklaerte Altbestaende
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
