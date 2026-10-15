# Inventur-App: lokale Android-Demo und eigenstaendiges Backend

## Stand und Grenzen

- Android ist eine lokale Demo mit frei erfundenen Artikeln und Zaehlerlisten.
  Der direkte SQL-Server-Zugriff und der JDBC-Treiber wurden entfernt.
  Der API-Platzhalter lehnt externe Aufrufe ab; eine Android-REST-Integration
  ist noch nicht implementiert.
- Demo-Daten liegen in `inventur_demo_offline.db`. Vorhandene Daten aus
  `inventur_offline.db` werden von der Demo nicht geoeffnet oder migriert.
  Lokale Mengen, Listendarstellung und Abschlussfunktionen bleiben vorhanden.
  Eine Mock-Bestaetigung ist KEINE Bestaetigung des Spring-Boot-Servers.
- Backend: Java 21, Spring Boot 4.1.1, PostgreSQL 17, Flyway V1 bis V9,
  elf fachliche Tabellen, JWT-Pruefung, datensatzbezogene Autorisierung,
  idempotentes Ergebnisspeichern, dauerhafte Konfliktarchivierung und interner
  ausdruecklicher Zaehlblattabschluss mit automatischem Sitzungsabschluss (Lernschritt 31).
- Noch offen: echte OIDC-Anbieter-/Benutzereinrichtung, Android-Anbindung,
  Konfliktbearbeitung, Neuzuweisung, Abschluss-API und Rundenstartablaeufe,
  produktive Bereitstellung. Keine ungesicherte Zwischenloesung verwenden.

Die Android-App und das Maven-Backend sind getrennte Projekte. Sie teilen
keine Zugangsdaten und haben noch keine funktionierende REST-Verbindung.
Weitere Backend-Vertraege stehen in [backend/README.md](backend/README.md).

## Voraussetzungen auf einem neuen Rechner

- Git sowie Android Studio mit Android SDK 36.1; Android-Minimum API 24.
- JDK 21 fuer Gradle und Maven. Java-Sprachniveau der Android-Quellen bleibt 11.
- Docker Desktop mit laufender lokaler Linux-Engine fuer PostgreSQL/Testcontainers.
- Fuer den Windows-Einrichtungshelfer zusaetzlich Python 3.
- Internetzugriff fuer Wrapper, Abhaengigkeiten und das PostgreSQL-Image.

Gradle Wrapper und Maven Wrapper liegen bei. Der Maven Wrapper verwendet
`only-script`; eine Wrapper-JAR ist dafuer nicht erforderlich.
Android Studio erzeugt `local.properties` mit dem SDK-Pfad lokal.
Keine alten SQL-Zugangswerte auf den neuen Rechner uebernehmen.

## Android starten und testen

Projektroot in Android Studio oeffnen, JDK 21 fuer Gradle waehlen und die App
auf Emulator/Geraet starten. Sie arbeitet im Demo-Modus.
Alternativ unter PowerShell im Projektroot:

```powershell
$env:JAVA_HOME = "$env:ProgramFiles\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest
```

Das Release-APK ist unsigniert. Private Signierschluessel gehoeren niemals in Git.
Cloud-Backup und Geraeteuebertragung von App-Daten sind ausgeschlossen. Eine
bewusste lokale Datensicherung/Exportfunktion ist noch nicht implementiert.
Keinen Build aus dem alten Unternehmensstand verteilen.

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

## Lokale Originale, Git-Index und sichere Uebernahme

Vor der Bereinigung wurde eine vollstaendige, gepruefte DPAPI-Rueckfallsicherung
ausserhalb des Projektverzeichnisses unter dem lokalen Benutzerprofil erstellt.
Die alten Bildressourcen sind nur dort erhalten. Die beiden ungeklaerten
SQL-Originale bleiben lokal und sind gezielt ignoriert. Sie werden nicht als
fiktive Demo-Daten ausgegeben. `CON` bleibt ebenfalls lokal ignoriert.
Neue Logo-/Hintergrundgrafiken bestehen aus selbst erstelltem XML.

Die Bereinigung fuehrt KEIN git add, commit oder push aus.
Der alte Index kann deshalb noch alte Quellen, Logo und SQL-Dateien enthalten.
Ignore-Regeln entfernen bereits vorgemerkte Dateien nicht automatisch.

Erst nach ausdruecklicher Freigabe kontrolliert:
1. Alte SQL-Originale und Firmenlogo ausschliesslich aus dem Index entfernen.
2. Geaenderte/entfernte Android-Dateien und .gitignore aktualisieren.
3. Neue README, XML-Demo-Ressourcen und das komplette Backend aufnehmen.
4. Vorgemerkte Inhalte erneut auf Geheimnisse, Vollstaendigkeit und ungeklaerte
   Unternehmensdaten pruefen. Keine lokalen Caches, Datenbanken oder Artefakte aufnehmen.
5. Erst danach Commit und Push gesondert freigeben.

## Verifikation der Bereinigung

Backend: 421 Tests ohne Fehler oder uebersprungene Tests, Maven package erfolgreich.
Android: Debug und unsigniertes Release kompilieren; vier Unit-Tests bestehen.
Ein Emulator-/Geraetetest ist zusaetzlich sinnvoll und wurde nicht ersetzt.
Der PostgreSQL-Helfer wird auf diesem bestehenden Rechner nur mit --check geprueft;
seine neue Containererstellung wird hier nicht ausgefuehrt.

