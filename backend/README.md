# InventurBackend

Eigenstaendiges Maven-Projekt mit Java 21 und Spring Boot 4.1.1.
Das Android-Projekt bleibt ein separates Gradle-Projekt.

## Aktueller Stand: Lernschritt 29

Der vorhandene PUT-Endpunkt archiviert jetzt geeignete Offline-Konflikte dauerhaft.
Es gibt keine neue Route und keine neue Migration. Die JSON-Antwort erfolgreicher
Ergebnisrequests bleibt unveraendert. Archivierung uebernimmt keine Zaehlmenge.

### Welche Ablehnungen werden archiviert?

`KonfliktErfassungService.konfliktcode()` ordnet ausschliesslich diese Pruefcodes
dem gleichnamigen SyncKonfliktCode zu:

| Pruef-/Konfliktcode | Bedeutung |
| --- | --- |
| ERGEBNISREVISION_VERALTET | Basisrevision passt nicht zum aktuellen Ergebnis |
| ZUWEISUNG_GEAENDERT | Uebermittelte Zuweisungsversion stimmt nicht mehr |
| SITZUNG_ABGESCHLOSSEN | Sitzung ist inzwischen abgeschlossen |
| BLATT_ABGESCHLOSSEN | Blatt ist inzwischen abgeschlossen |
| STATUSVERSION_VERALTET | Blatt-/Sitzungsstatusversion stimmt nicht mehr |

Zusaetzlich muss der Benutzer aktiv sein, aktuell ZAEHLER sein, die Liste aktuell
zugewiesen haben und die vollstaendige Zielhierarchie muss existieren und passen.
Die bisherigen Pruefungen koennen bei Version oder Abschluss frueh abbrechen.
Deshalb verifiziert pruefeKonfliktKontext() vor dem Archivieren unter denselben
Transaktionssperren Benutzer -> Liste -> Sitzung -> Blatt -> Position vollstaendig.
Nur verifizierte IDs werden als FK-Beziehungen gespeichert.

Kein Archiv entsteht fuer Syntax-/Typ-/UUID-Fehler, fehlende Authentifizierung,
inaktive/falsche Rolle, fremde Zuweisung, unbekannte Ressourcen, manipulierte
Zuordnungen, Request-ID-Wiederverwendung mit anderem Inhalt oder technische Fehler.
Auch bei einer fruehen Status-/Versionsablehnung darf eine falsche Hierarchie
nicht versehentlich als vertrauenswuerdiger Konflikt gespeichert werden.
Eine neue Aenderung des frueheren Zaehlers nach Neuzuweisung wird weiterhin mit
404 abgelehnt: Historischer Zugriff fuer solche neuen Inhalte benoetigt spaeter
einen gesonderten sicheren Prozess und wird hier nicht aus Clientangaben abgeleitet.

### Atomare Entscheidung und Replay

1. Spring Security prueft JWT und aktive lokale Identitaet; InventurPrincipal
   bestimmt den lokalen Urheber, niemals ein freies Clientfeld.
2. ErgebnisSpeichernService erkennt bekannte Entscheidungen vor aktueller
   fachlicher Pruefung. Fuer neue Requests bleiben Rolle, Zuweisung, Versionen,
   Hierarchie und die bestehende PostgreSQL-Sperrreihenfolge verbindlich.
3. Geeignete Konflikte erhalten nach vollstaendiger Kontextpruefung eine neue
   Registrierung in sync_request. Die globale Request-ID entscheidet auch bei
   Konkurrenz; nach Sperrwartezeiten wird Replay erneut geprueft.
4. KonfliktErfassungService speichert den exakten normalisierten Originalinhalt,
   SHA-256, Formatversion, Menge/Notiz, alle uebermittelten Revisionen/Versionen,
   Urheber und OFFEN-Status. Der Erfassungszeitpunkt stammt vom DB-Default.
5. Dieselbe Transaktion setzt sync_request auf ABGELEHNT und speichert die genaue
   409-Antwort. Der bestehende beforeCommit-Guard verhindert eine unfertige
   EINGEGANGEN-Registrierung. Es gibt weder Ergebnis- noch Historienaenderung.
6. Erst wenn TransactionTemplate erfolgreich committet hat, erreicht die
   Archivbestaetigung HTTP. Fehler rollen Archiv und Registrierung gemeinsam
   zurueck; eine technische Unsicherheit wird niemals als Archivierung quittiert.

V7 unterstuetzt bereits ABGELEHNT mit antwort_http_status und antwort_json.
V9 verlangt keine sync_request-Zeile fuer den Konflikt, erlaubt aber diesen
atomaren Ablauf mit einer verifizierten Ressourcenhierarchie. Es wird keine
zusaetzliche Schemaaenderung benoetigt. Bestehende Repositories und der bisherige
Wiederholungs-/Registrierungsservice werden verwendet.

Beispiel fuer HTTP 409 im unveraenderten Fehlerformat:

```json
{
  "status": 409,
  "code": "KONFLIKT_ARCHIVIERT_ERGEBNISREVISION_VERALTET",
  "detail": "Offline-Aenderung wurde als Konflikt archiviert. Zaehlergebnis wurde nicht geaendert."
}
```

Der Client erkennt Archivierung an HTTP 409 mit einem der fuenf
KONFLIKT_ARCHIVIERT_<Konfliktcode>-Werte. Ein beliebiges 409, beispielsweise
REQUEST_ID_REUSED, ist KEINE Archivbestaetigung. Andere Fehlercodes/HTTP-Status
und 200-Erfolgsantworten bleiben erhalten. Fehler enthalten keine fremden
Originalinhalte, Token oder technischen Interna.

Akzeptierte UND archiviert abgelehnte Requests reservieren ihre UUID dauerhaft.
Identische Wiederholung durch den urspruenglichen weiterhin aktiven angemeldeten
Benutzer gibt exakt den gespeicherten HTTP-Status/JSON-Text zurueck, selbst nach
Neuzuweisung, Rollenwechsel, Abschluss oder spaeterer Ergebnisrevision. Andere
Benutzer erhalten keine fremden Entscheidungen. Veraenderter Inhalt bei gleicher
ID liefert REQUEST_ID_REUSED. Auch wenn der Zustand spaeter eine Speicherung
erlauben wuerde, wird die alte Konfliktentscheidung nicht neu interpretiert.
Eine neue Korrektur braucht eine neue ID und passende aktuelle Revisionen.

Nur die NICHT archivierten ungueltigen/unberechtigten Ablehnungen bleiben ohne
dauerhafte Registrierung. Sie werden bei einer erneuten Anfrage neu geprueft.
Bei einem Verbindungs-/Commitfehler kann der Client das Ergebnis nicht sicher
kennen; derselbe unveraenderte Request klaert dies ueber den dauerhaften Replaypfad.

### Lernen, Grenzen und Tests

Ein Offline-Konflikt bewahrt Arbeit, die wegen eines inzwischen anderen
Serverzustands nicht sicher uebernommen werden kann. Die App soll spaeter
"Originalaenderung gesichert, noch nicht uebernommen" anzeigen und die lokale
Menge/Notiz bis zu einer bewussten Konfliktbearbeitung behalten. Sie darf eine
Archivbestaetigung nicht als erfolgreiche Zaehlung oder Anlass zum Loeschen der
lokalen Aenderung verwenden. Automatisches Anpassen der alten Basisrevision
wuerde den Konflikt verdecken und koennte neuere Arbeit ueberschreiben.

Originaldaten bleiben unveraendert, damit spaeter die urspruengliche Absicht und
der erste Ablehnungsgrund nachvollziehbar sind. Wiederholungen duerfen deshalb
keine zweite Archivzeile anlegen. Datenbank-Sperren, globale Request-ID und
UNIQUE-Constraints sichern das auch bei parallelen HTTP-Aufrufen; keine lokalen
Java-Sperren dienen als alleiniger Schutz.

24 neue echte HTTP-/PostgreSQL-Tests mit signierten Test-JWTs pruefen die fuenf
Konfliktgruende, NULL/0, Originaltext/Hash/Versionen, unveraenderte Ergebnisse,
dauerhaftes Replay, fremde/geaenderte Requests, Parallelitaet, atomare Rollbacks
und fehlende endgueltige Entscheidung. Bestehende Tests fuer fachliche Ablehnungen
wurden gezielt auf Archivierung angepasst; ihre Sicherheits- und Mengenpruefungen
bleiben bestehen. Das gemeinsame Test-Cleanup entfernt nur den eigenen isolierten
Testgraph und beruecksichtigt dessen neue Konflikt-FKs.

Abschliessender Maven-package-Build: 421 Tests, keine Fehler/Fehlschlaege und keine
uebersprungenen Tests; BUILD SUCCESS. Migrationen V1 bis V9 bleiben unveraendert.

Kein Zugriff auf inventur_dev oder Betriebsdatenbanken fuer diese Tests; keine
Konfliktdaten werden in die Entwicklungsdatenbank geschrieben. Noch keine
Konfliktanzeige/-bearbeitung, Zusammenfuehrung, neue Neuzuweisung oder Rundenlogik.
BL-012 benoetigt weiterhin eine spaetere fachliche Sperre fuer neue Runden.
Als naechster Schritt folgt auf ausdrueckliche Anweisung die Sicherheitspruefung
vor dem ersten Git-Commit; dieser Lernschritt fuehrt keine Git-Befehle aus.

## Vorheriger Stand: Lernschritt 28

V9 ergaenzt `zuweisung_historie` und `sync_konflikt`; damit sind die elf geplanten
fachlichen Tabellen vorhanden. Es gibt keine neuen Endpunkte, keine Neuzuweisungs-
oder Konfliktverarbeitung und keine Aenderung am ErgebnisSpeichernService.
Die bisherigen Aussagen zu nicht dauerhaft gespeicherten fachlichen Ablehnungen
gelten weiterhin: Der PUT-Endpunkt fuellt sync_konflikt noch nicht automatisch.

### Zuweisungshistorie

`ZuweisungHistorieEntity` dokumentiert UUID, gesamte Liste, vorherigen/neuen
Zaehler (jeweils nullable), Version vorher/nachher, verantwortlichen Benutzer,
serverseitigen Zeitpunkt und einen optionalen Sync-Request.
`ZuweisungHistorieRepository` liest nach Listen-ID und aufsteigender Version.

- NULL -> Zaehler: erste Zuweisung; Zaehler A -> B: Neuzuweisung;
  Zaehler -> NULL: Aufheben einer Zuweisung.
- Beide Zaehler muessen verschieden sein (IS DISTINCT FROM, auch fuer NULL).
- Version vorher >= 0; Version nachher exakt vorher + 1.
- UNIQUE (zaehlerliste_id, version_nachher) verhindert doppelte Versionsschritte.
- Benutzer- und Listen-FKs verhindern unbekannte Referenzen; keine Loeschkaskaden.
- Optionaler Request und Liste muessen zusammenpassen. V9 ergaenzt dazu den
  technischen UNIQUE-Schluessel (request_id, zaehlerliste_id) in sync_request.
  Bestehende Zeilen und Migrationen V1 bis V8 werden nicht umgeschrieben.

Diese Constraints allein aendern die aktuelle Zuweisung nicht und beweisen noch
keine lueckenlose Historienkette. Ein spaeterer Admin-Service muss Rolle und
Berechtigung pruefen, die Liste sperren, vorherige Werte aus dem Serverzustand
nehmen und aktuelle Zuweisung/Version sowie Historie atomar schreiben. Fuer den
optionalen Request sind dann auch Operation und verantwortlicher Urheber zu
pruefen. Historische Benutzer duerfen inzwischen eine andere Rolle besitzen.

### Synchronisierungskonflikte

`SyncKonfliktEntity` speichert UUID, urspruengliche Request-ID, Urheber, optionale
gepruefte Ressourcenverweise, Konfliktcode, unveraenderten kanonischen Inhalt,
Formatversion, SHA-256, Basis-/lokale Revision, uebermittelte Zuweisungs- und
Statusversionen, nullable Menge/Anmerkung, Serverzeit und Bearbeitungsstatus.
`SyncKonflikteRepository` bietet Abruf nach Ursprungsschluessel und nach Liste/
Status mit stabiler Zeit-/UUID-Sortierung.

Die urspruengliche Request-ID ist eine fachliche UUID ohne FK auf sync_request.
Eine abgelehnte Aenderung kann somit archiviert werden, obwohl keine Requestzeile
existiert. Ein Konflikt archiviert einen Inhalt, reserviert aber nicht automatisch
die Request-ID im bestehenden idempotenten Verarbeitungsablauf.

Die vier nullable Ressourcenbeziehungen bilden ausschliesslich einen bekannten,
verifizierten Kontext ab. Zusammengesetzte FKs und ein Hierarchie-CHECK erzwingen
Liste -> Sitzung -> Blatt -> Position. Bei unbekannten oder falsch zugeordneten
Zielen koennen die nicht verifizierbaren Verweise NULL bleiben; die urspruenglich
uebermittelten IDs bleiben vollstaendig im kanonischen Text erhalten. Insbesondere
darf spaeter nicht versucht werden, eine fremde/falsche Hierarchie unveraendert
in diese aufgeloesten FK-Spalten einzutragen. Listenzuweisungen sind kein Teil
dieser FK-Schluessel: Historische Konflikte bleiben nach Neuzuweisung gueltig.

- UNIQUE (urheber_id, urspruengliche_request_id, inhalt_format_version,
  inhalt_fingerprint) verhindert doppelte identische Urspruenge, unabhaengig von
  spaeterem Konfliktgrund oder Status. Veraenderter Inhalt bei wiederverwendeter
  Request-ID wird getrennt erhalten; verschiedene Benutzer werden nicht vermischt.
- PostgreSQL prueft 64 kleingeschriebene Hexzeichen UND den SHA-256 des exakten
  UTF-8-Texts, ohne zusaetzliche Erweiterung. SHA-256 ist keine Authentifizierung.
- Revisionen/Zuweisungsversion >= 0, Statusversionen >= 1, Formatversion >= 1.
- Menge >= 0 oder NULL; NULL bleibt ungezaehlt, 0 bleibt gezaehlt.
- Definierte Konfliktcodes: ZUWEISUNG_GEAENDERT, LISTE_NICHT_ZUGEWIESEN,
  FALSCHE_ZUORDNUNG, SITZUNG_ABGESCHLOSSEN, BLATT_ABGESCHLOSSEN,
  STATUSVERSION_VERALTET, ERGEBNISREVISION_VERALTET, ZIEL_NICHT_VORHANDEN,
  REQUEST_ID_REUSED. Authentifizierungs-/Syntaxfehler und technische Fehler sind
  damit kein fachlicher Konfliktarchiv-Eintrag.
- Bearbeitungsstatus: OFFEN, GELOEST, VERWORFEN. Das sind vorbereitete Statuswerte,
  noch kein implementierter Bearbeitungsprozess oder neue Ergebnisentscheidung.
- Alle FKs behalten PostgreSQL NO ACTION; keine CASCADE-/SET-NULL-Loeschaktionen.

Archiv-Originalfelder sind im JPA-Mapping nicht aktualisierbar. Die Zeitspalten
werden ausschliesslich durch den DB-Default clock_timestamp() eingefuegt und
stehen nach erneutem Laden/Refresh bereit. Weder Clients noch Entity-Konstruktoren
liefern diese Zeitpunkte. Ein privilegierter direkter SQL-Zugriff ist damit nicht
grundsaetzlich gegen Archivmanipulation gesperrt; es wurden keine Trigger oder
neuen Datenbankrollen eingefuehrt.

### Tests, Fachregeln und naechster Schritt

54 neue isolierte Testcontainers-Tests pruefen V9, Hibernate validate, elf Tabellen,
nullable Zuweisungen, Versionsfolge, optionale Requestreferenz, Fremdschluessel,
historische Daten nach Neuzuweisung, Originaltext/Hash, NULL/0, alle Codes/Status,
Duplikate und Loeschschutz. Die vier bisherigen Tests mit fester Migrationsanzahl
wurden ausschliesslich von acht auf neun erfolgreiche Migrationen angepasst.

Abschlusspruefung: Maven package erfolgreich; 397 Tests, davon 54 neu, keine
Fehler/Fehlschlaege/uebersprungenen Tests. V1 bis V8 wurden per SHA-256 gegen den
vorherigen Stand geprueft. Ein Zeitvergleich im neuen Test wurde auf die
PostgreSQL-Uhr umgestellt, um Windows-/Container-Uhr und Zeitgenauigkeit nicht
zu vermischen.

V9 wurde anschliessend mit dem vorhandenen DPAPI-geschuetzten start-dev.ps1
regulaer in inventur_dev angewendet (Flyway-Pruefsumme -134301264). V1 bis V8
behalten ihre erfolgreichen Eintraege und Pruefsummen. Beide neuen Tabellen
enthalten null Zeilen. Die bisherigen Bestandszahlen bleiben gleich: zwei
Demo-Listen, drei Demo-Benutzer, alle weiteren fachlichen Tabellen leer.
Health lieferte HTTP 200 mit {"status":"UP"}; Hibernate validate war erfolgreich.
Der nur fuer diese Pruefung gestartete Backend-Prozess wurde danach beendet.

BL-001 bis BL-012 bleiben fachliche Vorgaben: Zuweisungen betreffen die gesamte
Liste (BL-006), urspruenglicher Benutzer/Version bleiben nachvollziehbar,
Sitzungen/Runden und bewusste Blattabschluesse bleiben unveraendert, ebenso
serverbestaetigter Gesamtabschluss und NULL/0. Offline-Outbox, Quittierung und
Revisionspruefung werden nicht durch diese Tabellen ersetzt. Insbesondere
BL-012 (keine neue Runde bei ausstehenden Aenderungen oder Konflikten) benoetigt
spaeter Servicepruefungen und Kenntnis lokaler ausstehender Aenderungen.

Empfohlener naechster Schritt: Den Konflikterfassungsablauf zuerst festlegen,
einschliesslich Berechtigung, Verbindung zwischen Ablehnung und Archivierung,
erneuter Pruefung nicht reservierter IDs und atomarem Schutz vor Duplikaten.
Danach gezielt in den vorhandenen Speicherservice integrieren; neue
Konfliktbearbeitungs- oder Admin-Endpunkte erst in einem eigenen Lernschritt.
Eine automatische Zusammenfuehrung oder Ueberschreibung ist nicht implementiert.

## Vorheriger Stand: Lernschritt 27

### Ergebnis per REST speichern

`PUT /api/v1/sitzungen/{sitzungId}/positionen/{positionId}/ergebnis`
verwendet ausschliesslich den bestehenden ErgebnisSpeichernService. Die bisherigen
GET-Antworten und Migrationen V1 bis V8 bleiben unveraendert. Es gibt keine neue
Anmeldung, Rundenstart- oder Abschlussfunktion.

Erforderliche Header: `Content-Type: application/json` und
`Authorization: Bearer <Access-Token>` (Platzhalter, kein echter Token).
Beispiel mit ausschliesslich erfundenen IDs:

```http
PUT /api/v1/sitzungen/93000000-0000-4000-8000-000000000001/positionen/96000000-0000-4000-8000-000000000001/ergebnis
```

```json
{
  "requestId": "a1000000-0000-4000-8000-000000000001",
  "zaehlerlisteId": "92000000-0000-4000-8000-000000000001",
  "zaehlblattId": "94000000-0000-4000-8000-000000000001",
  "zuweisungVersion": 1,
  "basisRevision": 0,
  "lokaleRevision": 7,
  "erwarteteBlattStatusVersion": 1,
  "erwarteteSitzungStatusVersion": 1,
  "zaehlmenge": 0,
  "anmerkung": null
}
```

Alle zehn Felder muessen vorhanden sein, auch die beiden nullable Felder.
`null` bei zaehlmenge bedeutet ungezaehlt; `0` ist eine gueltige gezaehlte Menge.
UUIDs muessen die vollstaendige Standardform besitzen. Versionen/Revisionen sind
Ganzzahlen: Zuweisungs-, Basis- und lokale Revision mindestens 0, Statusversionen
mindestens 1, Menge zwischen 0 und Integer.MAX_VALUE oder null. Numerische
Strings, Nachkommastellen, Ueberlaeufe, unbekannte/doppelte Felder und weitere
JSON-Dokumente nach dem Objekt werden abgelehnt. Es gibt kein Client-Benutzerfeld.
ErgebnisSpeichernRequest liest gezielt den JSON-Baum, weil normale automatische
Jackson-Konvertierung diese strikten Typregeln nicht ausreichend garantiert.
Die bestehende kanonische Normalisierung bleibt unveraendert.

Beispiel einer gespeicherten Antwort (Zeitpunkt nur zur Veranschaulichung):

```json
{
  "requestId": "a1000000-0000-4000-8000-000000000001",
  "entscheidung": "AKZEPTIERT",
  "zaehlpositionId": "96000000-0000-4000-8000-000000000001",
  "bestaetigteLokaleRevision": 7,
  "ergebnisRevision": 1,
  "zaehlmenge": 0,
  "anmerkung": null,
  "geaendertAm": "2026-10-14T08:00:00Z"
}
```

### Sicherheit, Transaktion und Wiederholung

Spring Security prueft vor dem Controller das signierte JWT, Issuer, Audience,
Gueltigkeit und die aktive lokale OIDC-Zuordnung. Die lokale Benutzer-UUID kommt
aus InventurPrincipal. Der Controller bindet Pfad-IDs verbindlich in den
kanonischen Request ein und fuehrt selbst kein SQL aus.

Der bestehende Service erkennt zuerst eine bekannte Request-ID samt Urheber und
identischem kanonischen Inhalt. Erst neue Aenderungen werden gegen aktuelle
Rolle ZAEHLER, Listenzuweisung, Hierarchie, offene Status und Versionen geprueft.
Diese Pruefungen, Requestregistrierung, Ergebnis, Historie und gespeicherte
Entscheidung liegen in einer gemeinsamen Transaktion mit der bestehenden
Sperrreihenfolge. Eine Erfolgsantwort wird erst nach erfolgreichem Commit an
HTTP zurueckgegeben. Es gibt keine vorgeschaltete Zuweisungspruefung, die Replay
nach einer Neuzuweisung verhindern wuerde.

Identische Wiederholung durch den urspruenglichen weiterhin aktiven angemeldeten
Benutzer liefert den exakt gespeicherten JSON-Text und HTTP-Status ohne neue
Historienzeile. Dies gilt nach Neuzuweisung, Blatt-/Sitzungsabschluss und spaeteren
Revisionen. Auch ein inzwischen zu ADMIN gewechselter urspruenglicher Benutzer
kann nur seine alte Bestaetigung abrufen; neue Aenderungen erfordern ZAEHLER.
Andere Benutzer erhalten niemals die fremde gespeicherte Antwort.

Fachliche Ablehnungen neuer Requests werden weiterhin NICHT dauerhaft gespeichert
und reservieren keine Request-ID. Wiederholte Ablehnungen werden neu gegen den
aktuellen Zustand geprueft; eine zuvor abgelehnte ID kann deshalb nach Korrektur
akzeptiert werden. Sobald eine ID akzeptiert wurde, ist ihr Inhalt unveraenderlich.
Neue Mengen-/Versionsaenderungen benoetigen dann eine neue Request-ID.
Bei technischer Unsicherheit muss der Client denselben Request unveraendert
wiederholen: Ein Verbindungsfehler kann auch nach einem bereits erfolgten Commit
auftreten. Nur Replay klaert diesen Fall ohne doppelte Speicherung.

| HTTP | Bedeutung |
| --- | --- |
| 200 | Gespeichert oder identische gespeicherte Bestaetigung |
| 400 | Ungueltiger JSON-Vertrag oder Pfad-UUID |
| 401 | Fehlendes/ungueltiges JWT oder keine aktive lokale Zuordnung |
| 403 | Falsche Rolle; im Service festgestellter unzulaessiger Benutzerstatus |
| 404 | Fremde, fehlende oder falsch zugeordnete Ressource; auch fremde Request-ID |
| 409 | Versions-/Statuskonflikt, geaenderter Inhalt einer akzeptierten ID oder noch laufender Request |
| 415 | Content-Type wird nicht unterstuetzt |
| 503 | Technischer Speicherfehler, generische Antwort mit Retry-After: 1 |

Fehler haben einheitlich die Felder `status`, `code`, `detail`; keine internen
SQL-Fehler, fremden Daten, Requestinhalte oder Token werden ausgegeben.

### Lernbegleitung und Tests

PUT beschreibt das Setzen des Ergebnisses einer bereits bezeichneten Position.
POST wird haeufig fuer das Anlegen einer neuen Ressource oder eine Aktion
verwendet. Das Verb allein verhindert keine doppelten SQL-Aenderungen: Hier
leisten Request-ID, gespeicherte Entscheidung und Transaktion die Idempotenz.

Ein Request-DTO beschreibt die erlaubten Eingangsfelder. Android wird spaeter
eine lokal vorgemerkte Aenderung samt UUID und Versionen als JSON mit Bearer-Token
senden. Bei ausbleibender Antwort bleibt derselbe Request fuer einen erneuten
Versuch erhalten. Eine neue Benutzerkorrektur bekommt eine neue ID. Die
Android-Anbindung ist in diesem Lernschritt noch nicht implementiert.

45 neue echte HTTP-Integrationstests verwenden kryptografisch signierte Test-JWTs
und einen isolierten PostgreSQL-Testcontainer. Sie pruefen Eingaben, Identitaet,
Berechtigung, Revisionen, exaktes Replay, Parallelitaet und atomare Rollbacks bei
Fehlern in Historie und Requestentscheidung. Entwicklungs-/Betriebsdatenbanken
werden von diesen Tests nicht verwendet. Testschluessel und Testfixtures liegen
nur im Testquellpfad.

Abschliessender Maven-package-Build: 343 Tests, keine Fehler, keine Fehlschlaege,
keine uebersprungenen Tests; BUILD SUCCESS. Die bisherigen 298 Tests bleiben
erfolgreich. Migrationen V1 bis V8 wurden zusaetzlich per SHA-256 gegen den
vorhandenen Stand geprueft und sind unveraendert.

## Vorheriger Stand: Lernschritt 26

Alle vier vorhandenen fachlichen GET-Endpunkte pruefen jetzt die aktuelle
benutzerbezogene Leseautorisierung. Health bleibt oeffentlich. Die JWT-Pruefung
und das geschlossene Verhalten ohne vertrauenswuerdige Konfiguration aus Schritt
25 bleiben erhalten; es wurde kein Anbieter eingerichtet und kein Schreibzugriff
ueber HTTP freigegeben.

### Identitaet, Rolle und Ressourcenzuweisung

`LeseBerechtigung.aktuellerZaehler()` nimmt die lokale UUID ausschliesslich aus
dem authentifizierten `InventurPrincipal` im Spring-Security-Kontext. Ein freies
JSON-Feld, Header, Rollenclaim oder URL-Parameter bestimmt niemals den Benutzer.
Vor jeder fachlichen Service-Leseoperation prueft eine frische Skalarprojektion
aus BenutzerRepository den Aktivstatus und die Rolle ZAEHLER. Ein veralteter
JPA-Entity-Cache darf diese Pruefung nicht ersetzen. ADMIN hat auf diesen normalen
Zaehler-Endpunkten kein automatisches Leserecht.

Die bestehenden Leseservices verwenden readOnly-Transaktionen mit REPEATABLE READ.
Damit sehen Rollenpruefung, Zuweisung und geladene Daten bei einem regulaeren
HTTP-Serviceaufruf denselben PostgreSQL-Snapshot. Bereits laufende Leseoperationen
koennen ihren berechtigten Snapshot zu Ende lesen; neue Anfragen sehen eine
zwischenzeitlich bestaetigte Rollen- oder Zuweisungsaenderung. Interne Aufrufer
mit bereits laufender Transaktion muessen fuer denselben konsistenten Snapshot
auch deren Isolation entsprechend waehlen; Spring REQUIRED uebernimmt eine
bestehende Transaktion.

| GET-Endpunkt | Pruefung und Abfrage |
| --- | --- |
| `/api/v1/health` | Oeffentlich, unveraendert UP |
| `/api/v1/zaehlerlisten` | SQL/JPA filtert direkt auf aktuellerZaehler.id; Codesortierung bleibt |
| `/api/v1/zaehlerlisten/{listeId}/sitzungen` | Liste muss dem aktuellen Zaehler gehoeren; Sitzungsabfrage ebenfalls nach Eigentuemer gefiltert |
| `/api/v1/sitzungen/{sitzungId}/blaetter` | Sitzung ueber ihre Liste auf den aktuellen Zaehler pruefen; Blattabfrage ebenso eingeschraenkt |
| `/api/v1/sitzungen/{sitzungId}/blaetter/{blattId}` | Erste Blattabfrage filtert Blatt, Sitzungs-ID und Listeneigentuemer gemeinsam; erst danach Positionen, Artikel und Ergebnisse laden |

Ungefilterte Repository-Methoden fuer bestehende interne Daten-/Schema-Tests
bleiben vorhanden, werden aber von den fachlichen REST-Leseservices nicht benutzt.
Die vorhandenen DTOs, Feldnamen, Sortierungen, Datumsausgabe und null/0-Werte
sind unveraendert. Ohne Listenzuweisung liefert der Listenabruf HTTP 200 mit [].
Eine eigene vorhandene Liste ohne Sitzungen liefert ebenfalls [].

### HTTP-Verhalten

- Ohne gueltige JWT-Anmeldung oder mit deaktiviertem/unbekanntem Benutzer: 401.
- Aktiver angemeldeter ADMIN oder andere fehlende Zaehlerberechtigung: 403.
- Fremde, fehlende oder falsch zugeordnete Ressource: identische JSON-Antwort mit 404,
  RESSOURCE_NICHT_GEFUNDEN und Ressource nicht gefunden.
- Ungueltige UUID eines authentifizierten Leseaufrufs: 400, ohne Echo des Werts.
- Andere Pfade und alle Schreibmethoden bleiben standardmaessig gesperrt.

`LeseFehlerHandler` schreibt Ressourcen-/Parameterfehler direkt als JSON. Dadurch
werden keine fremden IDs, Daten oder interne Ursachen ausgegeben und kein zweiter
Error-Dispatch durch die Security-Kette ausgeloest. 401/403 bleiben beim bestehenden
SecurityFehlerHandler.

### Tests und Abfrageanzahl

Neue Integrationstests nutzen ausschliesslich einen eigenen Testcontainers-
PostgreSQL und echte HTTP-Anfragen mit kryptografisch signierten Test-JWTs. Sie
pruefen eigene/fremde/fehlende Ressourcen, falsche Hierarchie, leere Listen,
ungueltige UUIDs, ADMIN, Deaktivierung, Rollenwechsel bei gleichem Token und eine
Neuzuweisung der gesamten Liste. Ein zusaetzlicher interner Test beweist, dass ein
veralteter Benutzer-Entity-Cache den Rollenwechsel nicht verbergen kann.

Blattdetails benoetigen bei zwei oder zwoelf Positionen gleich viele Abfragen:
fuenf einschliesslich JWT-Benutzerzuordnung und aktueller Rollenpruefung. Ein leeres
Blatt benoetigt vier; die Ergebnisabfrage entfaellt. Ein fremdes Blatt fuehrt nur
zu Zuordnung, Rollenpruefung und eingeschraenkter Blattabfrage (drei Abfragen);
Artikel, Positionen und Ergebnisse werden dabei nicht geladen. Keine N+1-Abfragen.

Bestehende isolierte Controller-Vertragstests erhalten nur im Testcode einen
kontrollierten lokalen Principal. Direkte Service-Tests verwenden denselben
Testkontext. Die echte Security-Kette wird zusaetzlich mit signierten JWTs ueber
HTTP geprueft, ohne Testanmeldeweg in der produktiven Anwendung.

Normale Lesezugriffe folgen der aktuellen Zuweisung. Es werden keine lokalen
Offline-Aenderungen geloescht und keine Ergebnisse bei Neuzuweisung veraendert.
Die spaetere Replay-Sonderregel fuer den urspruenglichen Urheber wird hier nicht
als HTTP-Funktion implementiert; der interne ErgebnisSpeichernService bleibt
unveraendert. Keine Migration, Android-Aenderung oder Render-Bereitstellung.

Verifiziert: 298 Tests erfolgreich (272 bisherige und 26 neue), keine Fehler oder
uebersprungenen Tests; Maven `package` erfolgreich. Die bisherigen Sicherheitstests
wurden fuer die jetzt erlaubten, zugeordneten GET-Zugriffe angepasst. Testschluessel,
Testidentitaeten und Test-Seeds sind nicht im Backend-JAR enthalten. Flyway V1 bis
V8 sind per SHA-256 unveraendert. inventur_dev wurde weder verwendet noch veraendert.

Empfohlener naechster Schritt: einen authentifizierten Ergebnis-Schreib-Endpunkt
planen, einschliesslich der Replay-Sonderregel fuer den urspruenglichen Urheber.
Noch keine solche Route implementieren.

## Historischer Stand: Lernschritt 25

Der Starter `spring-boot-starter-security-oauth2-resource-server` ergaenzt die
Resource-Server-/JOSE-Unterstuetzung von Spring Security 7.1.1. Versionen bleiben
durch Spring Boot 4.1.1 verwaltet. Es wurde kein externer Anbieter eingerichtet.

### Fail Closed und externe Konfiguration

Die eigene `JwtDecoderKonfiguration` verwendet ausschliesslich folgende externe
Einstellungen, ohne hinterlegte Anbieterwerte oder Schluessel:

| Property | Umgebungsvariable |
| --- | --- |
| `inventur.security.jwt.issuer` | `INVENTUR_SECURITY_JWT_ISSUER` |
| `inventur.security.jwt.audience` | `INVENTUR_SECURITY_JWT_AUDIENCE` |
| `inventur.security.jwt.jwk-set-uri` | `INVENTUR_SECURITY_JWT_JWK_SET_URI` |

Wenn alle Einstellungen fehlen, startet die Anwendung mit einem Decoder, der
jeden Token ablehnt. Health bleibt ohne Token erreichbar. Teilweise gesetzte oder
ungueltige Einstellungen verhindern den Start. Issuer und JWKS-URI muessen gueltige
HTTPS-URLs ohne Benutzerinformationen oder Fragmente sein. Die Audience muss
explizit gesetzt sein. Es gibt keinen produktiven Testmodus, keinen lokalen
Schluessel-Fallback und keinen privaten Signaturschluessel im Backend.
Die vertraute JWKS-URI stammt aus der Konfiguration, niemals aus einem Token.
Ein spaeterer Ausfall der Schluesselquelle erlaubt keine ungepruefte Anmeldung.

### Tokenpruefung und lokale Identitaet

Der `NimbusJwtDecoder` prueft die Signatur mit ausdruecklich erlaubtem RS256.
Andere Algorithmen und unsignierte Tokens werden abgelehnt. Die gemeinsame
`InventurJwtValidierung` prueft Issuer exakt, eine passende API-Audience,
vorhandene Ablaufzeit und Subject sowie exp/nbf mit 30 Sekunden Uhrtoleranz.
`nbf` ist optional, wird bei Vorhandensein geprueft. Erlaubte Header-Typen sind
JWT und at+jwt; fehlende oder andere Typen sind ungueltig. Ein Header-Typ allein
unterscheidet keine ID-/Access-Tokens: Die spaetere Anbieter-Konfiguration muss
Access-Tokens fuer eine eigene API-Audience ausstellen. ID-Tokens der Android-
Client-Anmeldung duerfen nicht als API-Access-Tokens verwendet werden.

Nimbus kann numerisches sub zu Text umwandeln. Deshalb kontrolliert der kleine
`StrikterJwtDecoder` NACH der Signatur-/Validatorpruefung die Originaltypen aus
dem signierten Inhalt mit Nimbus. Issuer/Subject muessen echte Zeichenketten sein,
Zeitclaims numerische Werte und Audience eine Zeichenkette oder eine Liste von
Zeichenketten. Ungueltige Unicode-Surrogate oder NUL in Identitaeten werden
abgelehnt. Die kryptografische Pruefung bleibt vollstaendig bei Spring/Nimbus.

Nur der `JwtAuthenticationProvider` ruft danach den internen
`InventurJwtAuthenticationConverter` auf. Dieser uebergibt verifizierten Issuer
und Subject an `OidcBenutzerZuordnung`. Nur eine aktive, ausdruecklich vorhandene
Zuordnung erzeugt `InventurAuthentifizierung` mit `InventurPrincipal` und lokaler
Datenbank-UUID. Unbekannte und deaktivierte Konten liefern identisch HTTP 401.
Benutzer-IDs, Rollen und Scopes aus dem Token werden nicht als Inventurrechte
uebernommen. Der Principal enthaelt keinen Token; Credentials bleiben NULL.
Keine automatische Benutzeranlage und keine neue Verbindung zum Schreibservice.

Bearer-Tokens werden ausschliesslich aus dem Authorization-Header gelesen;
Query-Parameter und Formularfelder werden nicht zur Anmeldung verwendet. Die
bestehenden generischen JSON-Handler liefern 401/403 ohne Claims, Tokens oder
interne Ursachen. Die API bleibt ohne Cookie-Anmeldung und zustandslos.

### Fachliche Routen weiterhin vollstaendig gesperrt

Nur GET `/api/v1/health` ist oeffentlich. Alle anderen Anfragen werden mit denyAll
gesperrt, AUCH nach erfolgreicher JWT-Pruefung und aktiver Benutzerzuordnung.
Ohne gueltige Anmeldung gilt 401, mit gueltiger lokaler Identitaet 403. So wird
bis zur noch fehlenden benutzerbezogenen Leseautorisierung keine Liste freigegeben.
Es gibt weiterhin keinen HTTP-Schreib-Endpunkt.

Die bisherigen Controller-Antworttests sind nun isolierte MockMvc-Vertragstests
ohne Security-Filter (Helfer mit historischem Namen `AuthentifizierteTestAnfragen`).
Sie verwenden die echten Controller, vorhandenen JSON-Konverter und bei DB-Tests
die isolierte Testcontainers-Datenbank. Das schaltet die produktive Filterkette
nicht frei. Separate Security- und echte HTTP-Tests pruefen diese Filterkette mit
401/403. Die fachlichen Controller und DTO-Antworten bleiben unveraendert.

### Testschluessel und verbleibende Grenzen

`JwtTestSchluessel` erzeugt RSA-Schluessel nur im Test-Arbeitsspeicher. Ein
@TestConfiguration-Decoder benutzt den zugehoerigen oeffentlichen Testschluessel
mit denselben Produktionsvalidatoren. Diese Testkonfiguration ist nur explizit
im JWT-Integrationstest importiert. Es gibt keine Testschluessel in main,
Properties, Schluesseldateien oder produktiven Profilen. JWT-HTTP-Tests deaktivieren
die automatische MockMvc-Ausgabe, damit auch bei Fehlern keine Tokens ausgegeben
werden. Integrationen verwenden ausschliesslich Testcontainers, nicht inventur_dev.

Geprueft werden echte Signaturen, Manipulation/fremde Schluessel, andere Algorithmen,
Issuer/Audience/Zeiten/Pflichtclaims/Typen, fehlende oder unvollstaendige Konfiguration,
unbekannte/inaktive Benutzer, lokale Principal-UUID, ignorierte fremde Rollen-/ID-
Claims, Header-Anmeldung und trotz Anmeldung gesperrte Routen. Reale Anmeldung,
Anbieterbetrieb, Android-PKCE, Refresh-Tokens und Leseautorisierung fehlen weiterhin.
Flyway V1 bis V8 und die Android-App bleiben unveraendert.

Verifiziert: 272 Tests erfolgreich (234 bisherige und 38 neue), keine Fehler oder
uebersprungenen Tests; Maven `package` erfolgreich. Der echte HTTP-Test liefert
Health 200 und fuer fachliche Routen mit gueltig signiertem, lokal zugeordnetem
Testtoken 403. Fehlende/ungueltige Tokens liefern 401. Der ohne Anbieter konfigurierte
Anwendungsfall lehnt auch echt signierte Testtokens ab.
Das gebaute Backend-JAR enthaelt keine Testschluessel, Testauthentifizierung oder
Test-Seeds. Flyway V1 bis V8 sind per SHA-256 unveraendert. inventur_dev wurde
fuer diesen Schritt weder gestartet noch beschrieben.

Empfohlener naechster Schritt: benutzerbezogene Leseautorisierung anhand der lokalen
Principal-UUID und aktuellen Listenzuweisung, bevor fachliche Routen freigegeben werden.

## Historischer Stand: Lernschritt 24

V8 ergaenzt ausschliesslich `benutzer.oidc_issuer` und `benutzer.oidc_subject`
als optionale TEXT-Spalten. Ein CHECK verlangt entweder zwei vorhandene Werte
oder zwei NULL-Werte. Weitere CHECKs verbieten leere und ausschliesslich aus
Whitespace bestehende Werte, einschliesslich Tabs, Zeilenumbruechen und Unicode-
Leerzeichen. UNIQUE auf Issuer/Subject verhindert eine doppelte Zuordnung.
Mehrere Benutzer ohne externe Identitaet bleiben erlaubt. Es gibt keinen Seed,
keine automatische Benutzeranlage und keine automatische Verknuepfung.
Flyway V1 bis V7 bleiben unveraendert.

`BenutzerEntity` bildet die neuen optionalen Felder ab. Der bisherige Konstruktor
bleibt erhalten und setzt beide Werte auf NULL; ein weiterer Konstruktor erlaubt
eine explizite Zuordnung. Es wurden keine Beziehungen oder Rollen geaendert.
`BenutzerRepository.findByOidcIssuerAndOidcSubject` sucht die eindeutige Kombination.

`OidcBenutzerZuordnung.findeAktivenBenutzer(GepruefteOidcIdentitaet)` liest innerhalb
einer readOnly-Transaktion den Benutzer und liefert bei aktivem Konto dessen UUID.
Unbekannte Identitaeten und inaktive Konten liefern identisch Optional.empty(),
ohne fremde Benutzer-/Listendaten oder unterschiedliche Fehlerdetails. Die
Komponente schreibt nicht, aendert keine Rollen und erteilt keine Listenrechte.
Auch die Identitaet eines Admins kann zugeordnet werden; daraus folgt kein Recht,
als Zaehler Ergebnisse zu speichern.

### Vertrauensgrenze

`GepruefteOidcIdentitaet` ist ein interner Traeger fuer die spaeter bereits durch
eine Sicherheitskomponente geprueften Claims. Sein Konstruktor kontrolliert nur
Pflichtwerte und Whitespace. Er prueft weder Signaturen noch Tokenherkunft.
Issuer/Subject werden nicht getrimmt, kleingeschrieben oder anderweitig normalisiert.

Ein beliebiger String oder ein konstruierter Record beweist keine Anmeldung.
Der neue Service ist mit keinem Controller oder Authentifizierungsfilter verbunden
und darf spaeter nur nach echter Tokenpruefung aufgerufen werden. Er erzeugt
keinen GepruefterUrheber. Die kuenftige Kette lautet: echte Access-Tokenpruefung ->
gepruefte Identitaet -> aktive lokale Zuordnung -> GepruefterUrheber -> fachliche
Berechtigungspruefung. SecurityKonfiguration aus Schritt 23 bleibt unveraendert:
nur GET /api/v1/health ist oeffentlich; fachliche Routen bleiben ohne echte Anmeldung
gesperrt. Keine OIDC-Anbieteranbindung, keine Tokenpruefung und kein neuer Endpunkt.

### Lernen und Tests

Issuer bezeichnet den ausstellenden Identitaetsanbieter; Subject ist dessen stabile
Benutzerkennung. Erst die Kombination identifiziert einen Benutzer bei einem
bestimmten Anbieter. Dieselbe Subject-Zeichenkette bei einem anderen Issuer darf
einen anderen Benutzer bezeichnen. E-Mail-Adressen sind kein Ersatz fuer diese
dauerhafte Zuordnung. Ein externer Benutzer benoetigt weiterhin eine ausdruecklich
eingerichtete lokale Berechtigung mit Rolle, Aktivstatus und Listenzuweisung.

Die neuen Tests laufen ausschliesslich gegen eine isolierte PostgreSQL-17-
Testcontainers-Datenbank. Neben V8 und Hibernate-Validierung pruefen sie den echten
Upgrade eines eigenen Testschemas von V7 auf V8, eindeutige und ungueltige Paare,
NULL-/Whitespace-Regeln, Entity-Roundtrip, aktive/inaktive/unbekannte Identitaeten
sowie unveraenderte Rollen und Zuweisungen. Beispielidentitaeten verwenden nur
erfundene Werte unter der reservierten Domain `.example`.

Verifiziert: 234 Tests erfolgreich (206 bisherige und 28 neue), keine Fehler oder
uebersprungenen Tests; Maven `package` erfolgreich. Vier bestehende Tests wurden
lediglich hinsichtlich der Gesamtanzahl erfolgreicher Migrationen von 7 auf 8
aktualisiert. Alle bisherigen HTTP- und fachlichen Pruefungen bleiben erfolgreich.

V8 wurde nach erfolgreicher Kontrolle der V1-V7-Historie ueber das vorhandene
DPAPI-geschuetzte Entwicklungsstartskript regulaer in `inventur_dev` angewendet.
V8-Flyway-Checksum: -165345273. Technische Vorher-/Nachher-Pruefsummen bestaetigen
unveraenderte drei Benutzer samt Rollen/Aktivstatus und zwei Listenzuweisungen.
Alle bestehenden OIDC-Zuordnungen bleiben NULL. Health liefert HTTP 200 mit UP,
Listen ohne Anmeldung HTTP 401. Es wurden keine Entwicklungsbenutzer angelegt.
Der nur fuer diese Pruefung gestartete Backend-Prozess wurde anschliessend beendet.
Maven spring-boot:run und das Startskript melden die gezielte Prozessbeendigung als
Fehler; dies ist kein Migrations- oder Startfehler. Der Maven-package-Build war erfolgreich.

Empfohlener naechster Schritt: die echte Access-Tokenpruefung mit konfiguriertem
Issuer und API-Audience vorbereiten, zunaechst anhand isolierter Testschluessel.
Die Zuordnung allein ermoeglicht weiterhin keine Anmeldung.

## Historischer Stand: Lernschritt 23

Spring Security 7.1.1 ist ueber die Spring-Boot-4.1.1-Starter
`spring-boot-starter-security` und `spring-boot-starter-security-test` eingebunden.
Der Test-Starter hat ausschliesslich Test-Scope. Versionen werden vom vorhandenen
Spring-Boot-Parent verwaltet. Es gibt noch keinen OIDC-Anbieter und keine Tokenpruefung.

### HTTP-Sicherheitsgrenze

`SecurityKonfiguration` definiert eine explizite `SecurityFilterChain` fuer alle
Requests. Ausschliesslich GET `/api/v1/health` ist oeffentlich. Alle anderen Pfade
und Methoden benoetigen Authentifizierung, einschliesslich unbekannter Routen,
`/login`, `/error` und moeglicher Debug-/Actuator-Pfade. Noch existiert kein echter
Anmeldeweg: Im regulaeren Betrieb bleiben alle fachlichen Endpunkte gesperrt.

Die API arbeitet mit `STATELESS`: keine Anmeldung aus einer HTTP-Sitzung und
kein Speichern einer Anmeldung in einer Sitzung. Request-Cache, HTTP Basic,
Formularanmeldung und Logout sind deaktiviert. Ein expliziter, ausschliesslich
ablehnender AuthenticationManager verhindert die automatische Demo-Benutzeranlage.
Er ist kein Anmeldeverfahren und muss spaeter durch die echte Tokenpruefung
ersetzt werden. Kein Passwort oder Token ist im Produktivcode hinterlegt.

CSRF ist fuer diese reine API deaktiviert, weil weder Cookie-Anmeldung noch andere
automatisch vom Browser gesendete Anmeldeinformationen verwendet werden. Die
spaetere Bearer-Authentifizierung erfolgt ueber einen expliziten Authorization-
Header. STATELESS allein waere keine ausreichende Begruendung fuer deaktiviertes
CSRF. Eine kuenftige Cookie-/Browser-Anmeldung benoetigt eine neue CSRF-Bewertung.

`SecurityFehlerHandler` implementiert AuthenticationEntryPoint und AccessDeniedHandler.
Die einheitliche Antwort besteht aus `status`, `code`, `detail`:

```json
{"status":401,"code":"AUTHENTIFIZIERUNG_ERFORDERLICH","detail":"Eine gueltige Anmeldung ist erforderlich."}
```

401 bedeutet fehlende/ungueltige Anmeldung. Der Header `WWW-Authenticate: Bearer`
benennt den vorgesehenen spaeteren Anmeldeweg, aktiviert aber keine Tokenpruefung.
403 bedeutet fehlende Berechtigung bei authentifiziertem Zugriff und liefert
`ZUGRIFF_VERWEIGERT`. Interne Ausnahmeursachen und Requestinhalte werden nicht
ausgegeben oder durch diese Handler geloggt. Eine produktive Rollenpruefung ist
noch nicht vorhanden; der vorbereitete 403-Handler wird deshalb isoliert getestet.

### Tests und verbleibende Grenzen

Die bisherigen fachlichen API-Tests nutzen MockMvc mit Spring Security und einer
expliziten Testidentitaet. Der Helfer `AuthentifizierteTestAnfragen` existiert nur
unter `src/test/java`, setzt keinen Anmeldeweg fuer Netzwerkrequests auf und wird
nicht mit dem Backend ausgeliefert. Die echte SecurityFilterChain bleibt aktiv.
Diese Testidentitaet beweist weder Tokenpruefung noch eine BenutzerEntity-Zuordnung.
Die vorhandenen DTOs, Controllerantworten und fachlichen Services sind unveraendert.

Neue Sicherheitstests pruefen den oeffentlichen Health-Endpunkt, gesperrte API-/
Login-/Debug-Routen, das JSON-Fehlerformat, fehlende Demo-Benutzer, ignorierte alte
Sitzungsidentitaeten, abgewiesene frei uebergebene Anmeldeheader und den 403-Handler.
Ein echter HTTP-Test startet das Backend gegen Testcontainers und prueft Health
mit 200 sowie Listen ohne Testidentitaet mit 401. PostgreSQL-Tests bleiben isoliert;
`inventur_dev` wird nicht verwendet. Flyway V1 bis V7 bleiben unveraendert.

Naechste Schritte: echte Bearer-Tokenpruefung, vertrauenswuerdige Identitaetszuordnung,
aktive Benutzerpruefung und benutzerbezogene Autorisierung der Lesezugriffe. Bis
dahin fachliche Routen nicht oeffentlich freigeben. Kein neuer fachlicher Endpunkt,
keine Android-Anpassung, keine Migration und keine Render-Bereitstellung.

Verifiziert mit Maven `package`: 206 Tests erfolgreich (188 bisherige und 18 neue),
keine Fehler oder uebersprungenen Tests. Das erstellte Backend-JAR enthaelt weder
den Testauthentifizierungs-Helfer noch Security-Testbibliotheken oder Testcontainers.
Es wurden keine echten Anmeldungen getestet.

## Historischer Stand: Lernschritt 21

`ErgebnisSpeichernService.speichere(requestId, gepruefterUrheber, inhalt)` ist die
erste interne Ergebnisverarbeitung. Aufruf ausschliesslich ueber den Spring-Bean
und mit einer bereits vertrauenswuerdig geprueften Identitaet. Der Record
`GepruefterUrheber` selbst authentifiziert niemanden. Es gibt keinen neuen
Controller und keinen oeffentlichen Schreib-Endpunkt.

### Eine Speicherung, eine Transaktion

Nach der Inhaltsvalidierung startet ein `TransactionTemplate` eine eigene
schreibfaehige READ-COMMITTED-Transaktion. `@Transactional(NEVER)` am Einstieg
verbietet eine bereits laufende aufrufende Transaktion: Die Erfolgsantwort darf
erst nach dem wirklichen Commit zurueckkehren. Die vorhandenen Pruef- und
Registrierungsservices sowie das neue Schreibrepository arbeiten mit `MANDATORY`
innerhalb derselben Transaktion und derselben DataSource.

1. Bekannte Request-ID pruefen und eine gespeicherte Entscheidung unveraendert
   zurueckgeben, bevor die heutigen Berechtigungen oder Revisionen geprueft werden.
2. Fuer neue Requests in der bestehenden Reihenfolge sperren:
   Benutzer -> Liste -> Sitzung -> Blatt -> Position -> vorhandenes Ergebnis.
3. Nach der Pruefung nochmals auf Replay pruefen. Ein konkurrierender identischer
   Request kann waehrend der Sperrwartezeit gespeichert haben; dessen Antwort
   hat Vorrang vor einem jetzt festgestellten Revisionskonflikt.
4. Nur bei gueltiger neuer Aenderung die Request-ID atomar registrieren. Diese
   Registrierung folgt den Positionssperren, um FK-Sperr-Upgrades zu vermeiden.
5. Aktuelles Ergebnis aktualisieren oder mit eigener UUID anlegen; Basisrevision
   plus genau 1 speichern. Die vorhandene Ergebnis-UUID bleibt bei Updates erhalten.
6. Genau eine Historienzeile anfuegen und den Request als `AKZEPTIERT` abschliessen.
7. Commit; erst danach die Antwort an den internen Aufrufer zurueckgeben.

`ErgebnisSpeicherRepository` nutzt wie das Pruefrepository JdbcTemplate auf der
JPA-Transaktionsverbindung. Bestehende Entities und Migrationen bleiben unveraendert.
Die Positionssperre schuetzt auch die erste Speicherung ohne Ergebniszeile; der
bestehende UNIQUE-Constraint verhindert zusaetzliche aktuelle Ergebniszeilen.
Updates pruefen zusaetzlich die Basisrevision. Der serverseitige Zeitpunkt kommt
aus PostgreSQL und wird identisch in Ergebnis, Historie und Antwort uebernommen.
Der Benutzer kommt ausschliesslich aus `GepruefterUrheber`.

### Antwort und Wiederholung

`ErgebnisSpeichernAntwort` enthaelt `requestId`, `entscheidung`, `zaehlpositionId`,
`bestaetigteLokaleRevision`, `ergebnisRevision`, `zaehlmenge`, `anmerkung` und
`geaendertAm`. Ein eigener JsonMapper serialisiert die Antwort einmal. Dieser
exakte JSON-Text wird mit HTTP-Metadatum 200, Code `AKZEPTIERT` und Zeitpunkt im
Sync-Request gespeichert; es handelt sich noch nicht um einen HTTP-Schreibvertrag.

Beispiel: Basisrevision 0, lokale Revision 7, Menge 12 -> Ergebnisrevision 1,
eine aktuelle Ergebniszeile und eine Historienzeile. Derselbe Request erneut
liefert den urspruenglichen JSON-Text und Zeitpunkt; es bleibt bei Revision 1.
Ein neuer Request mit Basisrevision 1 erzeugt Revision 2 und eine zweite
Historienzeile. Null bleibt ungezaehlt, 0 ist gueltig, Notizen sind unabhaengig
von der Menge. Eine fehlende Ergebniszeile zaehlt als Ausgangsrevision 0.

`ErgebnisSpeicherergebnis` unterscheidet die dauerhaft gespeicherte `entscheidung`
und das Replay-Flag von einer aktuellen Ablehnung ohne gespeicherte Entscheidung.
Fachliche Ablehnungen werden in diesem Schritt **nicht dauerhaft registriert**.
Sie reservieren keine Request-ID; ein spaeterer gueltiger Versuch kann neu geprueft
werden. Die Pruefcodes aus Schritt 20 bleiben erhalten. Bekannte fremde IDs geben
`NICHT_BERECHTIGTER_ZUGRIFF`, geaenderte gueltige Inhalte `REQUEST_ID_REUSED` und
vorhandene unentschiedene Requests `NOCH_IN_BEARBEITUNG` ohne fremde Antwort zurueck.
Syntaktisch ungueltige Inhalte und negative Mengen werden bereits vor Replay
abgelehnt. Bereits gespeicherte akzeptierte oder abgelehnte Entscheidungen werden
weiterhin exakt durch den vorhandenen Wiederholungsservice wiedergegeben.

Technische Fehler, auch ein Fehler beim Commit, werden als Ausnahme weitergegeben
und nicht als dauerhafte fachliche Ablehnung gespeichert. Ergebnis, neue Historie
und Registrierung werden gemeinsam zurueckgerollt. Die bestehende Registrierung
verhindert zusaetzlich den Commit einer unentschiedenen neuen Requestzeile.
Bei verlorener Antwort kann dieselbe Request-ID sicher erneut versucht werden.

### Grenzen und Tests

Historie wird ausschliesslich durch neue INSERTs erweitert. Die Anwendung aendert
keine frueheren Revisionen; eine DB-weite Sperre gegen administrative Aenderungen
ist damit noch nicht implementiert. Fuer spaetere schreibende Services gilt
dieselbe Sperrreihenfolge; Deadlocks oder Verbindungsfehler bleiben technische,
wiederholbare Fehler. Es gibt noch keine automatische technische Wiederholung,
Authentifizierung, Abschluss- oder Rundenstartfunktion.

Die neuen Integrationstests nutzen ausschliesslich eine isolierte PostgreSQL-17-
Testcontainers-Datenbank. Sie pruefen Mengen und Notizen, Revisionen und Replay,
fachliche Ablehnungen, beide Reihenfolgen konkurrierender Neuzuweisung/Abschluesse,
identische Requests, zwei Requests auf derselben Basisrevision sowie vollstaendige
Rollbacks bei Historien-, Entscheidungs- und Commit-Guard-Fehlern.
`inventur_dev` wird fuer diesen Lernschritt nicht beschrieben.

Verifiziert: 188 Tests erfolgreich (159 bisherige und 29 neue), keine Fehler,
keine uebersprungenen Tests. Bestehende HTTP-Tests und Hibernate-Schemavalidierung
bleiben erfolgreich. Flyway V1 bis V7 sind unveraendert.

## Historischer Stand: Lernschritt 20

`ErgebnisBerechtigungsPruefer` ist eine interne Autorisierungs- und Revisionspruefung
fuer `ERGEBNIS_SPEICHERN`. Er nimmt `GepruefterUrheber` und das Inhaltsmodell
entgegen, validiert es mit dem vorhandenen Normalisierer und schreibt keine Daten.
Es gibt keine neuen Endpunkte, Authentifizierung, Mengenverarbeitung oder Migration.

Geprueft werden Benutzerexistenz, aktives Konto und ausdruecklich die Rolle
`ZAEHLER` (kein automatisches ADMIN-Schreibrecht), Listenexistenz, aktueller
zugewiesener Zaehler und Zuweisungsversion. Danach werden die gesamte Hierarchie
Liste/Sitzung/Blatt/Position, offene Sitzungs- und Blattzustaende sowie beide
erwarteten Statusversionen geprueft. Abschliessend muss `basisRevision` genau
der aktuellen Ergebnisrevision entsprechen. Eine fehlende Ergebniszeile bedeutet
logisch Revision 0 und wird nicht angelegt. Null und 0 sind gueltige Mengen;
negative Mengen werden ausdruecklich abgelehnt. Lokale Revisionen bleiben
Requestmetadaten und ersetzen nicht die serverseitige Basisrevision.

Die Rueckgabe `ErgebnisPruefergebnis` enthaelt ausschliesslich einen Code und
eine daraus abgeleitete `zulaessig()`-Abfrage. Codes:

- `ZULAESSIG`
- `BENUTZER_NICHT_VORHANDEN`, `BENUTZER_INAKTIV`, `FALSCHE_ROLLE`
- `LISTE_NICHT_ZUGEWIESEN`, `ZUWEISUNG_GEAENDERT`
- `FALSCHE_ZUORDNUNG`, `ZIEL_NICHT_VORHANDEN`
- `SITZUNG_ABGESCHLOSSEN`, `BLATT_ABGESCHLOSSEN`
- `STATUSVERSION_VERALTET`, `ERGEBNISREVISION_VERALTET`
- `UNGUELTIGE_ZAEHLMENGE`, `UNGUELTIGER_REQUEST`

Versionskonflikte bedeuten jede Abweichung, nicht nur numerisch kleinere
Client-Versionen. Fehler enthalten keine fremden IDs, aktuellen Versionen oder
Ergebniswerte. Bei einer nicht zugewiesenen Liste wird abgebrochen, bevor weitere
Sitzungs-, Blatt- oder Ergebnisdetails gelesen werden. Die internen Codes sind
noch kein oeffentlicher HTTP-Vertrag; die spaetere HTTP-Abbildung muss insbesondere
Existenzinformationen ueber fremde Ressourcen angemessen zusammenfassen.

### Sperren und gemeinsame Transaktion

Die Methode verlangt eine laufende schreibfaehige Transaktion (`MANDATORY`).
Ein eigenstaendiger Pruefaufruf ohne solche Transaktion oder in READ ONLY wird
abgelehnt. `ZULAESSIG` ist nur innerhalb dieser Transaktion gueltig; nach ihrem
Ende sind alle Sperren aufgehoben und eine neue Pruefung ist erforderlich.

Das kleine neue `ErgebnisPruefRepository` verwendet die vorhandene JdbcTemplate-
Unterstuetzung und denselben Spring/JPA-transaktionalen DataSource-Zugriff.
Die bestehenden JPA-Repositories mussten nicht veraendert werden. Direkte
gesperrte SELECT-Snapshots verhindern, dass ein bereits geladener, veralteter
JPA-Entity-Cache einen inzwischen geaenderten Serverstand verdeckt.

Die verbindliche Reihenfolge lautet:

1. Benutzer: `FOR SHARE`.
2. Liste: `FOR SHARE`.
3. Sitzung: `FOR SHARE`.
4. Blatt: `FOR SHARE`.
5. Position: ausdruecklich `FOR UPDATE`.
6. Vorhandenes Ergebnis: `FOR UPDATE`.

Die gemeinsamen Sperren schuetzen Konto/Rolle, Zuweisung und Status gegen
konkurrierende Updates. Die exklusive Positionssperre serialisiert Pruefungen
derselben Position. Sie schuetzt auch fehlende Ergebniszeilen: Der Fremdschluessel
eines konkurrierenden ersten Ergebnis-INSERTs benoetigt eine KEY-SHARE-Sperre auf
die Position, die mit deren FOR-UPDATE-Sperre kollidiert. Ein vorhandenes Ergebnis
wird zusaetzlich gesperrt, damit seine Revision nicht unbemerkt erhoeht wird.
Das echte Warten beider Faelle wird in PostgreSQL getestet.

Fuer die spaetere Erstverarbeitung muss die Pruefung vor dem Request-INSERT aus
Lernschritt 19 ihre Sperren erwerben. Sonst koennen die FK-KEY-SHARE-Sperren
zweier bereits registrierter Requests beim nachtraeglichen Hochstufen auf
FOR UPDATE gegeneinander blockieren. Alle spaeteren Schreibservices muessen
eine abgestimmte globale Sperrreihenfolge verwenden; Deadlock-/Timeout- und
Serialisierungsfehler erfordern einen kontrollierten Retry der gesamten Transaktion.

Eine moegliche spaetere Orchestrierung liest zunaechst bereits bekannte
Requestentscheidungen (diese bleiben trotz spaeterer Neuzuweisung wiederholbar).
Fuer neue Requests erwirbt sie die fachlichen Pruefsperren, registriert atomar
und beruecksichtigt dann erneut das Registrierungsergebnis. Eine zwischenzeitlich
gespeicherte identische Entscheidung hat Vorrang vor einer erneuten Verarbeitung.
Erst der Gewinner darf innerhalb derselben Transaktion Ergebnis, Revision,
Historie und Requestentscheidung schreiben. Nicht vorhandene/falsch zugeordnete
Ziele muessen dabei vor INSERTs mit ungueltigen Fremdschluesseln behandelt werden.
Diese Orchestrierung wird in Lernschritt 20 noch nicht implementiert.

Ein Integrationstest kombiniert gueltige Pruefung und Requestregistrierung in
derselben Transaktion und rollt beides bewusst zurueck. Der Commit-Schutz aus
Lernschritt 19 bleibt bestehen: Ohne endgueltige Entscheidung kein Commit einer
neuen Registrierung. Die Komponente erhoeht selbst keine Revisionen und ersetzt
keine Authentifizierung. Eine echte Identitaetspruefung muss der vertrauenswuerdige
interne Aufrufer bereitstellen; ein oeffentlicher HTTP-Zugang ist unzulaessig.

Pruefergebnis: 159 Tests erfolgreich (53 datenbankfreie und 106 PostgreSQL-
Integrationstests). 13 neue Unit-Tests und 17 PostgreSQL-Tests pruefen alle
fachlichen Ablehnungen, fehlende Ergebniszeilen, Null/0-Mengen, frische DB-Snapshots
sowie echte Konkurrenz bei Neuzuweisung, Sitzungs-/Blattabschluss und Ergebnis-
revisionen. Zwei konkurrierende pruefende Schreiber werden serialisiert und der
zweite erkennt die inzwischen geaenderte Revision. Testmutationen aendern nur
fiktive Berechtigungs-/Status-/Revisionsdaten in Testcontainers, keine Zaehlmengen.
Die eigenen Testfixtures werden isoliert wieder entfernt.

Keine Fehler oder uebersprungenen Tests. Alle bisherigen Endpunkte und Tests
bleiben erfolgreich, V1 bis V7 sind anhand ihrer Dateipruefsummen unveraendert.
Die Entwicklungsdatenbank wurde nicht verwendet und kein Entwicklungsserver
gestartet. Empfohlener naechster Schritt: Die interne Ergebnisverarbeitung mit
dieser Sperrreihenfolge orchestrieren und Ergebnis, Historie sowie Requestentscheidung
atomar verbinden; weiterhin ohne oeffentlichen Schreib-Endpunkt.

## Grundlage aus Lernschritt 19

`RequestRegistrierungsService` registriert intern neue Request-IDs atomar in
PostgreSQL. Es gibt weiterhin keinen REST-Schreib-Endpunkt, keine Authentifizierung
und keine Mengen-, Historien-, Abschluss- oder Rundenstartverarbeitung.

Die Methode `registriere` erfordert eine bereits laufende, schreibfaehige Spring-
Transaktion (`Propagation.MANDATORY`). Sie startet bewusst keine eigenstaendige
Registrierungstransaktion. Der spaetere fachliche Service soll Registrierung,
Ergebnis, Historie und endgueltige Entscheidung in derselben Transaktion speichern.
Ohne aufrufende Transaktion oder bei READ ONLY wird vor dem INSERT abgebrochen.

Die Eingaben bleiben Request-ID, intern gepruefter Urheber und das Inhaltsmodell
fuer `ERGEBNIS_SPEICHERN`. Der Inhalt wird mit der vorhandenen Formatversion 1
normalisiert. Das Repository speichert Request-ID, Urheber, vollstaendigen Ziel-
kontext, Versionen, kanonischen Inhalt und Fingerprint zusammen:

```sql
INSERT INTO sync_request (...) VALUES (..., 'EINGEGANGEN', statement_timestamp())
ON CONFLICT (request_id) DO NOTHING;
```

Nur der Request-ID-Konflikt wird behandelt; andere Constraintverletzungen bleiben
Fehler und brechen die Transaktion ab. Ein normales fehlerhaftes INSERT wuerde die
PostgreSQL-Transaktion in einen Fehlerzustand versetzen. Ein anschliessendes SELECT
in derselben Transaktion waere dann nicht einfach moeglich. `ON CONFLICT` vermeidet
diesen Fehlerzustand fuer die erwartete Konkurrenz um die Request-ID.

Bei einer neu eingefuegten Zeile ist die Rueckgabe `NEU_REGISTRIERT`, ohne
Antwortdaten. Das ist nur eine vorlaeufige Verarbeitungsfreigabe innerhalb dieser
laufenden Transaktion, keine erfolgreiche Mengenquittung. Bei bereits belegter ID
wird der vorhandene `RequestWiederholungsService` mit einem neuen SELECT verwendet:
identische entschiedene Requests liefern die gespeicherte Entscheidung; geaenderte
Inhalte liefern `REQUEST_ID_KONFLIKT` / `REQUEST_ID_REUSED`; andere Urheber erhalten
`NICHT_BERECHTIGTER_ZUGRIFF`, passende offene Requests `NOCH_IN_BEARBEITUNG`.
Nur `BEKANNTE_ENTSCHEIDUNG` darf Antwortinformationen enthalten.

### Keine dauerhaft verwaisten neuen Registrierungen

Fuer jede neue Zeile wird ein `beforeCommit`-Pruefschritt registriert. Er flusht
die JPA-Aenderungen und liest den Status mit einer nativen Abfrage, unabhaengig
von eventuell veralteten Entity-Cachewerten. Ein Commit ist nur zulaessig, wenn
die Zeile auf `AKZEPTIERT` oder `ABGELEHNT` steht. Die bestehenden V7-Constraints
fordern dann auch eine vollstaendige Entscheidung, Antwort und Verarbeitungszeit.
Fehlt die endgueltige Entscheidung, wird eine Ausnahme ausgeloest und die ganze
Transaktion zurueckgerollt. Die Registrierung ist danach nicht mehr vorhanden.
Auch beim expliziten Rollback verschwindet sie; ein Wiederholungsversuch kann
erneut registrieren. Keine Zwischenregistrierung wird separat committed.

Folglich kann der Registrierungsservice derzeit nicht alleine erfolgreich eine
neue Zeile committen. Die endgueltige fachliche Verarbeitung ist noch nicht
implementiert. Nur die isolierten Tests simulieren eine endgueltige Demo-
entscheidung mittels SQL, ohne Zaehlmengen oder Historien zu veraendern.
Eine endgueltige Antwort darf erst nach erfolgreichem Commit der gesamten
aufrufenden Transaktion veroeffentlicht werden.

### Konkurrenz und Wiederanlauf

Bei PostgreSQL READ COMMITTED wartet ein zweiter INSERT derselben Request-ID auf
das Ende der ersten Transaktion. Committed diese, fuegt der zweite nichts ein und
liest danach die gespeicherte Entscheidung bzw. erkennt abweichenden Inhalt oder
Urheber. Rollt die erste zurueck, kann die wartende Transaktion selbst neu
einfuegen. Die eindeutige DB-Bedingung wirkt auch zwischen mehreren Backend-
Instanzen; es werden keine lokalen Java-Sperren eingesetzt.

Die Tests beobachten das echte PostgreSQL-Lock-Warten zweier unabhaengiger
Transaktionen. Je Konkurrenzfall entsteht genau eine Zeile. Bereits entschiedene
Zeilen werden nicht ueberschrieben. Ein nach ON CONFLICT unerwartet verschwundener
Request wird nicht als neue Verarbeitungsfreigabe behandelt, sondern fuehrt zum
Fehler/Rollback. Hoehere Isolationsstufen oder Serialisierungsfehler erfordern
spaeter einen Retry der gesamten Transaktion; dieser Ablauf ist fuer READ COMMITTED
entworfen und getestet. Timeout-, Retry- und HTTP-Fehlerregeln folgen spaeter.

Bereits anderweitig committed gespeicherte EINGEGANGEN-Zeilen werden weiterhin
nicht automatisch uebernommen oder geloescht: Das waere ohne weiteren fachlichen
Kontext unsicher. Fuer solche Alt-/Fremdzeilen ist ein gesonderter kontrollierter
Wiederanlauf festzulegen. Die neue Registrierung selbst verhindert diesen Zustand
durch die gemeinsame Transaktion und die Commit-Pruefung. Eine Migration ist
dafuer nicht notwendig; direkte Repository-/SQL-Schreibwege koennen die interne
Service-Regel jedoch umgehen und muessen spaeter entsprechend begrenzt werden.

Pruefergebnis: 129 erfolgreiche Tests (40 datenbankfreie Tests und 89 isolierte
PostgreSQL-Integrationstests). Acht neue Unit-Tests und zwoelf PostgreSQL-Tests
pruefen Registrierung, EINGEGANGEN, gleiche/veraenderte Requests, andere Urheber,
Commit-Pruefung, Rollback und konkurrierende Commit-/Rollback-Faelle. Keine Fehler
oder uebersprungenen Tests. Alle bestehenden REST-Endpunkte bleiben unveraendert.
V1 bis V7 sind anhand ihrer Dateipruefsummen unveraendert. Die Entwicklungsdatenbank
wurde nicht verwendet und kein Entwicklungsserver gestartet.

Empfohlener naechster Schritt: Fachliche Berechtigungs-, Zuweisungs- und
Revisionspruefungen fuer die interne Ergebnisverarbeitung definieren und testen;
danach Ergebnis, Historie und Requestentscheidung atomar verbinden. Ein
oeffentlicher Schreib-Endpunkt folgt erst nach einer echten Identitaetspruefung.

## Grundlage aus Lernschritt 18

`RequestWiederholungsService` ist eine ausschliesslich interne, transaktionale
Wiederholungspruefung fuer `ERGEBNIS_SPEICHERN`. Er registriert keine neuen
Requests, aendert keine Mengen und erzeugt weder Historie noch Entscheidungen.
Es gibt keinen neuen REST-Endpunkt.

Eingaben sind Request-ID, `GepruefterUrheber` und `ErgebnisSpeichernInhalt`.
Der Urheber-Record kennzeichnet eine Vertrauensgrenze, implementiert aber selbst
keine Authentifizierung. Nur ein interner, vertrauenswuerdiger Aufrufer darf ihn
nach Identitaetspruefung erstellen; niemals aus einem ungeprueften Client-Feld.
Es gibt keine fest hinterlegte Pseudoidentitaet fuer oeffentliche Endpunkte.

Der Service normalisiert den Inhalt und sucht die Request-ID. Bei einer bekannten
ID wird zuerst der urspruengliche Urheber verglichen. Erst danach werden Format,
SHA-256, der vollstaendige kanonische Text und die gespeicherten Kontextspalten
(Operation, Ziel-IDs, Zuweisungsversion und erwartete Versionen) verglichen.
Ein passender finaler Request liefert eine unveraenderliche Kopie seiner
gespeicherten Entscheidung, auch wenn es eine Ablehnung war.

`RequestPruefergebnis` unterscheidet:

| Status | Bedeutung |
| --- | --- |
| `BEKANNTE_ENTSCHEIDUNG` | Gespeicherte Entscheidung mit Code, HTTP-Status, JSON und Zeitpunkt |
| `NEUER_REQUEST` | Zum Abfragezeitpunkt unbekannt; keine Reservierung oder Verarbeitung |
| `REQUEST_ID_KONFLIKT` | Wiederverwendete ID mit abweichendem Format/Inhalt/Kontext; `REQUEST_ID_REUSED` |
| `NOCH_IN_BEARBEITUNG` | Passender Request steht auf `EINGEGANGEN`; keine Erfolgsmeldung |
| `NICHT_BERECHTIGTER_ZUGRIFF` | Anderer Urheber; keine gespeicherten Inhalts- oder Antwortinformationen |

Nur `BEKANNTE_ENTSCHEIDUNG` darf Antwortdaten enthalten. Dies wird auch durch den
Konstruktor des Ergebnis-Records abgesichert. `GespeicherteRequestEntscheidung`
beschreibt ausschliesslich eine vorher gespeicherte Annahme oder Ablehnung,
keine neue erfolgreiche Speicherung von Zaehlmengen.

`SyncRequestsRepository.findeZurWiederholungspruefung` verwendet
`PESSIMISTIC_READ`. PostgreSQL sperrt die vorhandene Requestzeile fuer diesen
Lesevorgang (`SELECT FOR SHARE`). Parallele Leser sind moeglich, eine konkurrierende
Aenderung muss mit ihnen koordiniert werden. Eine bereits laufende Aenderung
laesst die Abfrage bis zum Commit oder Rollback warten. Es gibt keine
in-memory Locks als alleinigen Schutz.

Der Service verwendet `@Transactional` mit der Standardpropagation `REQUIRED`.
Ohne bestehende Transaktion wird eine neue erzeugt; andernfalls wird die
aufrufende Transaktion verwendet. Die Sperre bleibt bis zu deren Ende erhalten.
Bewusst kein `readOnly=true`: PostgreSQL erlaubt solche Zeilensperren nicht in
einer READ ONLY-Transaktion. Auch ein aeusserer Aufrufer darf deshalb keine
READ ONLY-Transaktion erzwingen. Die Komponente fuehrt trotzdem keine Schreib-
operation aus. Fehler werden nicht in eine Erfolgsentscheidung umgewandelt;
Laufzeitfehler fuehren nach den Spring-Regeln zum Rollback. Eine nachgelagerte
Antwort darf erst nach erfolgreichem Ende einer aeusseren Transaktion veroeffentlicht
werden, nicht aufgrund einer nur intern sichtbaren unbestaetigten Entscheidung.

Die wichtigste Grenze: Fuer eine unbekannte ID existiert noch keine sperrbare
Zeile. Zwei parallele Abfragen koennen beide `NEUER_REQUEST` liefern. Dieser
Status ist keine exklusive Verarbeitungsfreigabe. Erst eine spaetere atomare
Registrierung mit PostgreSQL-PK/UNIQUE und Konkurrenzbehandlung darf eine neue
fachliche Verarbeitung beanspruchen. Ein UNIQUE-Fehler darf nicht einfach in
derselben bereits abgebrochenen PostgreSQL-Transaktion ignoriert werden.
V7 reicht fuer diese Wiederholungspruefung aus; keine Migration ist notwendig.

Neun datenbankfreie Tests pruefen die Klassifikation und die Nichtoffenlegung
fremder Antworten. Zehn neue PostgreSQL-Tests pruefen identische Wiederholungen,
unveraenderte Zeilen, parallele bekannte/unbekannte Abfragen sowie echte DB-
Sperrwartefaelle bei Commit und Rollback. Nach einer zurueckgerollten neuen
Entscheidung wird deren ID wieder als unbekannt erkannt. Es werden in diesen
Tests nur fiktive Requests manuell vorbereitet, keine Mengen gespeichert.

Pruefergebnis: 109 erfolgreiche Tests, davon 32 datenbankfreie Tests und
77 isolierte PostgreSQL-Integrationstests. Keine Fehler oder uebersprungenen Tests.
Alle bisherigen Endpunkte und Antworten bleiben unveraendert. V1 bis V7 sind
anhand ihrer Dateipruefsummen unveraendert. Die Entwicklungsdatenbank wurde nicht
verwendet und kein Entwicklungsserver gestartet.

Empfohlener naechster Schritt: Atomare Registrierung neuer Request-IDs und Tests
gegen konkurrierende Erstverarbeitung, weiterhin intern und ohne Mengen-
aenderungen oder oeffentlichen Schreib-Endpunkt. Authentifizierung, aktuelle
Berechtigungen, fachliche Konfliktpruefungen und die gemeinsame Transaktion aus
Ergebnis, Historie und Entscheidung folgen in eigenen Schritten.

## Grundlage aus Lernschritt 17

Eine isolierte Komponente im Paket `service.sync` normalisiert ausschliesslich
`ERGEBNIS_SPEICHERN`. Es gibt keine neuen Endpunkte oder Requestverarbeitung.

- `ErgebnisSpeichernInhalt`: internes Record-Modell mit vollstaendigem Zielkontext.
- `ErgebnisRequestNormalisierer`: Validierung, kanonisches JSON und Java-SHA-256.
- `NormalisierterRequest`: Inhaltsformatversion, normalisierter Inhalt und Fingerprint.
- `ErgebnisRequestNormalisiererTests`: datenbankfreie Unit-Tests mit festem JSON/Hash.
- Ein zusaetzlicher Test in `SyncRequestIntegrationTests` speichert und laedt das
  erzeugte Resultat ausschliesslich in Testcontainers gegen das unveraenderte V7.

### Definition von Inhaltsformatversion 1

Die kanonische Darstellung ist kompaktes JSON ohne Einrueckung, BOM oder
abschliessenden Zeilenumbruch. Die Feldreihenfolge ist verbindlich:

1. `formatVersion`
2. `operation`
3. `zaehlerlisteId`
4. `zaehlsitzungId`
5. `zaehlblattId`
6. `zaehlpositionId`
7. `zuweisungVersion`
8. `basisRevision`
9. `lokaleRevision`
10. `erwarteteBlattStatusVersion`
11. `erwarteteSitzungStatusVersion`
12. `zaehlmenge`
13. `anmerkung`

Alle Identitaeten sind explizit angegeben: Listen- und Blatt-ID wurden zusaetzlich
aufgenommen, weil eine isolierte Komponente diese nicht aus einer Positions-ID
nachschlagen kann. Die Richtigkeit der Zuordnung muss spaeter der Service/DB
pruefen; die Komponente prueft nur Vollstaendigkeit und Wertebereiche.

UUIDs werden mit `UUID.toString()` als kleine Hexzeichen mit Bindestrichen
ausgegeben, Enums mit `name()`. Nur Formatversion 1 und Operation
`ERGEBNIS_SPEICHERN` werden akzeptiert; unbekannte Versionen oder andere
Operationen werden abgelehnt. Zahlen erscheinen exakt dezimal ohne Anfuehrungszeichen,
Exponenten oder fuehrende Nullen. Versionen nutzen `Long`, damit fehlende
Pflichtwerte nicht still auf 0 fallen. Zuweisungsversion, Basisrevision und lokale
Revision sind mindestens 0, beide erwarteten Statusversionen mindestens 1.
Die optionale INTEGER-Zaehlmenge ist null oder nichtnegativ.

Die kanonische Darstellung enthaelt alle Felder, auch explizite Nullwerte.
Fehlende/null Pflichtangaben werden abgelehnt. Nullable Menge und Anmerkung
duerfen null sein; beim Aufbau des internen Modells koennen fehlende optionale
Angaben auf null abgebildet werden. `null`, `0`, `""` und `" "` bleiben
unterschiedlich. Anmerkungen werden weder getrimmt noch hinsichtlich Unicode oder
Zeilenumbruechen vereinheitlicht. Sonderzeichen, Umlaute und gueltige Emoji bleiben
erhalten; JSON-Steuerzeichen, Anfuehrungszeichen und Backslashes werden durch
Jackson escaped. Nicht-ASCII-Zeichen und Schraegstriche werden nicht unnoetig
escaped; notwendige Unicode-Hex-Escapes verwenden grosse Hexbuchstaben.
Ungepaarte UTF-16-Surrogate werden abgelehnt, um keine stillen UTF-8-Ersetzungen
und damit ungewollt zusammenfallende Inhalte zu erzeugen.

SHA-256 wird mit `MessageDigest` ueber die UTF-8-Bytes des kanonischen JSON
berechnet und als 64 kleine Hexzeichen ausgegeben. Die Formatversion ist Teil
dieser Bytes und wird zusaetzlich separat zurueckgegeben. Die eigene feste
Jackson-Konfiguration haengt nicht von der HTTP-JSON-Konfiguration ab.
Der feste Vergleichstest soll Formatveraenderungen bei Bibliotheksupdates erkennen.

Die Komponente nimmt ein Java-Record entgegen, keinen rohen HTTP-Request.
Unterschiedliche JSON-Feldreihenfolgen beim vorherigen Aufbau desselben Records
aendern das Ergebnis nicht; dies ist getestet. Strikte HTTP-Eingabepruefung
(z.B. unbekannte Felder, doppelte JSON-Schluessel, Dezimalzahlen statt Ganzzahlen,
Ueberlaeufe und Zwangskonvertierungen) muss spaeter vor dem Modellaufbau erfolgen.

Nicht enthalten sind Request-ID, Benutzer-/Urheber-ID, Authentifizierungstokens,
HTTP-Header, Geraeteangaben, Antwortdaten und technische Zeitpunkte.
`ERGEBNIS_SPEICHERN` hat aktuell keinen fachlichen Zeitparameter. Daher wird
keine Instant-Normalisierung implementiert. Zusaetzliche fachliche Felder oder
Operationen erfordern spaeter explizite Regeln und gegebenenfalls eine neue
Inhaltsformatversion. Der authentifizierte Urheber wird unabhaengig vom Inhalt
geprueft und niemals aus einer ungeprueften Client-Benutzerangabe uebernommen.
SHA-256 vergleicht Inhalte und ist keine Authentifizierung.

### Fiktives Vergleichsbeispiel

Die folgenden exakt einzeiligen UTF-8-Bytes sind das Hash-Eingabeformat:

```json
{"formatVersion":1,"operation":"ERGEBNIS_SPEICHERN","zaehlerlisteId":"10000000-0000-4000-8000-000000000001","zaehlsitzungId":"70000000-0000-4000-8000-000000000001","zaehlblattId":"82000000-0000-4000-8000-000000000001","zaehlpositionId":"84000000-0000-4000-8000-000000000001","zuweisungVersion":1,"basisRevision":0,"lokaleRevision":1,"erwarteteBlattStatusVersion":1,"erwarteteSitzungStatusVersion":1,"zaehlmenge":12,"anmerkung":"Demo: 12 Stück"}
```

Der zugehoerige SHA-256-Fingerabdruck lautet:

```text
622483adf44787a849f018de58d5dd211782ee1dabf5cdfa51ab723f34ab7c49
```

Die Ausgabefelder passen unveraendert zu V7: `normalisierter_inhalt`,
`inhalt_fingerprint`, `inhalt_format_version`. `basisRevision` entspricht
spaeter `erwartete_ergebnisrevision`. Die lokale Revision bleibt im gespeicherten
normalisierten Inhalt; ein weiteres DB-Feld ist fuer diesen Lernschritt nicht
noetig. Bestehende V7-Demo-Fixtures sind reine Strukturtests und wenden nicht
nachtraeglich eine Normalisierung auf bereits gespeicherte Requests an.

Spaeter sind Request-ID und Inhaltsvergleich getrennt zu behandeln: Die ID findet
den urspruenglichen Vorgang, der kanonische Inhalt/Fingerprint prueft seine
Unveraendertheit. Erst Authentifizierung, Berechtigungs- und Versionspruefung,
atomare Speicherung, konkurrierende Wiederholungsbehandlung und die gespeicherte
Antwort machen daraus eine sichere Synchronisierung.
Pruefergebnis: 90 Tests erfolgreich, davon 23 datenbankfreie Tests (21 neue
Normalisierungstests und 2 HTTP-Tests) sowie 67 PostgreSQL-Integrationstests.
Keine Fehler und keine uebersprungenen Tests. Der neue V7-Kompatibilitaetstest
speichert nur einen fiktiven Request in der wegwerfbaren Testdatenbank und wird
zurueckgerollt. Es wurden keine echten Zaehlmengen gespeichert. Die bestehenden
REST-Endpunkte, Maven-Abhaengigkeiten und Migrationen V1 bis V7 bleiben unveraendert.
In diesem Lernschritt wurde keine Verbindung zu inventur_dev hergestellt und
kein Entwicklungsserver gestartet.

## Grundlage aus Lernschritt 16

V7 ergaenzt `sync_request` und `ergebnis_historie`. Es wurden keine neuen
REST-Endpunkte oder Verarbeitungsservices eingefuehrt.

`sync_request` speichert die globale UUID `request_id`, den urspruenglichen
Urheber, Operation, Liste und optionale Sitzung/Blatt/Position, die uebermittelte
Zuweisungsversion sowie optionale erwartete Ergebnis-, Blatt- und Sitzungsversionen.
Zulaessige Operationen sind `ERGEBNIS_SPEICHERN`, `BLATT_ABSCHLIESSEN`,
`BLATT_WIEDEROEFFNEN`, `RUNDE_STARTEN` und `LISTE_ZUWEISEN`.
Eine Startanfrage darf noch keine Sitzungs-ID besitzen. Ein Blattverweis erfordert
eine Sitzung, ein Positionsverweis ein Blatt. Zusammengesetzte Fremdschluessel
pruefen, dass die angegebenen Objekte wirklich zu derselben Hierarchie gehoeren.
Dafuer fuegt ausschliesslich V7 technische UNIQUE-Schluessel auf bestehende
ID/Eltern-ID-Paare hinzu; die Migrationen V1 bis V6 bleiben unveraendert.

Gespeichert werden normalisierter Inhalt als TEXT, ein SHA-256-Fingerprint als
64 kleine Hexzeichen und `inhalt_format_version`. Diese technische Formatversion
macht spaetere Aenderungen der Normalisierungsregeln erkennbar. Die DB prueft
Form und Pflichtwerte, berechnet oder verifiziert den Fingerprint aber nicht.
Der spaetere Service muss den fachlichen Inhalt kanonisieren, den Fingerprint
berechnen und bei Wiederholung zusaetzlich Urheber, Operation, Referenzen und
Versionsangaben mit dem urspruenglichen Request vergleichen. Die konkrete
Normalisierung und das genaue Request-JSON sind noch festzulegen.

Requestzustaende sind `EINGEGANGEN`, `AKZEPTIERT` und `ABGELEHNT`.
Ein eingegangener Request hat noch keine Entscheidung oder Antwort.
Ein entschiedener Request benoetigt Entscheidungscode, HTTP-Status, Antworttext
und Verarbeitungszeit. Die Verarbeitungszeit darf nicht vor dem Eingang liegen.
`antwort_json` ist bewusst TEXT, damit der Antwortinhalt unveraendert gespeichert
werden kann. JSON-Gueltigkeit, operationstypische Felder, passende HTTP-Codes
und gegebenenfalls benoetigte Antwortheader muessen spaeter im Service/Vertrag
festgelegt und geprueft werden.

`ergebnis_historie` speichert UUID, Position, Request, akzeptierte Revision,
nullable Menge/Anmerkung, urspruenglichen Benutzer und serverseitigen Zeitpunkt.
Akzeptierte Aenderungsrevisionen beginnen bei 1; Revision 0 bleibt der
Ausgangsstand aus Lernschritt 15. UNIQUE-Regeln auf Position/Revision und
Request/Position verhindern doppelte Revisionen bzw. denselben Historieneintrag.
Ein zusammengesetzter Fremdschluessel stellt sicher, dass Request, Position und
Benutzer zusammenpassen. Null und 0 bleiben verschieden; negative Mengen
werden abgelehnt. Es gibt keine Loeschkaskaden.

`SyncRequestEntity` und `ErgebnisHistorieEntity` verwenden ausschliesslich
unidirektionale LAZY-Beziehungen. `SyncRequestsRepository.findById` liest einen
Request; `ErgebnisHistorieRepository` liest Positionshistorien nach Revision.
Eine spaetere Neuzuweisung der Liste aendert weder den gespeicherten Urheber
noch dessen damalige Zuweisungsversion. Authentifizierung und die damalige
Berechtigung werden von diesen Fremdschluesseln nicht bewiesen.

Der bisherige UUID-Wert `zaehlblatt.letzter_status_request_id` erhaelt in V7 einen
zusammengesetzten Fremdschluessel, der auch die Blatt-ID prueft. Vor der Migration
wurde verifiziert, dass keine bereits belegten Requestverweise existieren.
Die wechselseitigen DB-Verweise sind beherrschbar: Blatt mit NULL-Verweis anlegen,
Request zum existierenden Blatt speichern, danach den Blattverweis setzen.
Keine bidirektionale JPA-Beziehung wurde ergaenzt. Der spaetere Blattservice muss
zusaetzlich einen passenden akzeptierten Status-Operationstyp pruefen.

BL-001/002/006 werden durch gespeicherten Urheber/Version, unveraenderte Ergebnisse
und die gemeinsame Listenzuordnung vorbereitet. BL-003/007/008 behalten getrennte
Sitzungen und Runden. BL-004/005 benoetigen weiterhin eine dauerhafte Android-
Warteschlange und echte Quittierungen. BL-009/010 behalten bewusste Blattabschluesse
und die Null/0-Unterscheidung. Fuer BL-011 gibt es keinen separaten Gesamtabschluss-
Request; der spaetere Blattabschluss-Service muss den Sitzungsabschluss ableiten.
BL-012 (keine neue Runde bei ausstehenden Aenderungen/Konflikten) bleibt eine
spaetere fachliche Pruefung und wird nicht durch diese Tabellen allein garantiert.

Eine Transaktion speichert spaeter Requestentscheidung, aktuellen Ergebnisstand
und Historie gemeinsam oder verwirft alles. Erst ein Service mit Berechtigungs-,
Versions-, Wiederholungs- und Konkurrenzpruefungen kann Idempotenz garantieren.
Die Tabellen verhindern nicht jede absichtliche Aenderung/Loeschung eines
Historieneintrags; append-only-Verwendung und DB-Rechte sind spaeter abzusichern.

Beispiel fuer das spaetere Ziel: Ein Zaehler sendet 12 Stueck mit Request-ID R.
Der Server speichert Entscheidung, Ergebnis und eine Historienzeile gemeinsam.
Geht die Antwort verloren, sendet die App denselben Request R erneut. Bei gleichem
Urheber und Inhalt soll der Server die gespeicherte Antwort wiedergeben, ohne
eine zweite Revision anzulegen. Veraenderter Inhalt unter R muss abgelehnt werden.
Dieses Verhalten wird in Lernschritt 16 noch nicht implementiert.
Pruefergebnis: 68 erfolgreiche Tests (2 datenbankfreie HTTP-Tests und 66 isolierte
PostgreSQL-Integrationstests), keine Fehler oder uebersprungenen Tests. Die zwoelf
neuen Strukturtests pruefen Requests, optionale Startreferenzen, Originalentscheidungen,
Neuzuweisung, Status/Operationen, Versionen, die Objektzuordnung, Historieneindeutigkeit,
Null/0-Mengen, Loeschschutz und den Blatt-Request-Verweis. Die bisherige
Blatt-Testbereinigung wurde an die wechselseitigen Fremdschluessel angepasst.
Alle bestehenden Endpunkte und deren Antworten bleiben unveraendert.
V7 wurde nach den erfolgreichen Tests regulaer ueber den sicheren Entwicklungsstart
auf `inventur_dev` angewendet. Flyway-Historie und zusammengesetzte Fremdschluessel
sind verifiziert; `sync_request` und `ergebnis_historie` sind dort leer. Es wurden
keine fachlichen Requests oder Historien angelegt und keine bestehenden Daten
geloescht oder ueberschrieben. V1 bis V6 sind anhand ihrer Dateipruefsummen
unveraendert. Der temporaere Backend-Server wurde nach der Pruefung beendet.

## Grundlage aus Lernschritt 15

V6 ergaenzt ausschliesslich `zaehlergebnis`: UUID, verpflichtende Positions-ID,
optionale nichtnegative Zaehlmenge, optionale Anmerkung, verpflichtende
nichtnegative BIGINT-Revision sowie optionalen aendernden Benutzer und Zeitpunkt.
Der UNIQUE-Constraint auf `zaehlposition_id` erlaubt hoechstens einen aktuellen
Ergebnisdatensatz je Position. Fremdschluessel sichern Position und Benutzer ab;
es gibt keine Loeschkaskaden und keine zusaetzliche Sitzungszuordnung.

`ZaehlergebnisEntity` besitzt eine unidirektionale `@OneToOne(fetch = LAZY)`-
Beziehung zur Position und eine optionale `@ManyToOne(fetch = LAZY)`-Beziehung
zum Benutzer. `Integer` erhaelt die Unterscheidung zwischen `null` (ungezaehlt)
und `0` (gezaehlt mit Menge 0). Auch ein ungezaehltes Ergebnis darf eine
Anmerkung besitzen. Die Revision ist ein vorbereitetes fachliches Versionsfeld,
kein automatisch verwaltetes JPA-`@Version`-Feld; Revision 0 steht fuer den
noch nicht serverseitig geaenderten Ausgangsstand.

`ZaehlergebnisseRepository` laedt ein Ergebnis nach Positions-ID und alle
vorhandenen Ergebnisse eines Blatts gesammelt. Der Blattdetail-Service erweitert
jede `ZaehlpositionResponse` um `ergebnis` mit `ZaehlergebnisResponse`:

```json
{"zaehlmenge": null, "anmerkung": null, "revision": 0}
```

Dieser logische Ausgangsstand wird bei fehlender Ergebniszeile zurueckgegeben,
ohne eine Zeile anzulegen. Vorhandene Ergebnisse liefern ihre gespeicherten
Werte, beispielsweise `{"zaehlmenge":0,"anmerkung":"Demo: leer gezaehlt","revision":3}`.
Alle bisherigen Blatt- und Positionsfelder sowie die Positionsreihenfolge
bleiben erhalten. Die anderen Endpunkte bleiben unveraendert.

Der Detailabruf verwendet drei SELECTs: Blatt, Positionen samt Artikeln und
gesammelte Ergebnisse. Ein Test misst mit Hibernate-Statistiken dieselbe
Abfragezahl bei 2 und 12 Positionen einschliesslich gespeicherter Ergebnisse.
Ein leeres Blatt braucht nur zwei SELECTs. Die Statistiken sind ausschliesslich
in der neuen Integrationstestklasse aktiviert, nicht in der Anwendung.

Es gibt weiterhin keine Schreib-Endpunkte, automatische Revisionserhoehung,
Ergebnishistorie, Sync-Request-Tabelle, Konfliktpruefung, Abschluesse oder
Rundenstarts. Diese Schritte muessen spaeter Berechtigung, Request-Identitaet,
erwartete Revision und atomare Speicherung gemeinsam beruecksichtigen.
Die Endpunkte sind weiterhin ausschliesslich fuer lokale Entwicklung vorgesehen.
Pruefergebnis: 56 erfolgreiche Tests, davon 2 datenbankfreie HTTP-Tests und
54 isolierte PostgreSQL-Integrationstests. Keine Fehler, keine uebersprungenen
Tests. Vorherige JSON-Pruefungen wurden nur um das neue Ergebnisfeld erweitert;
alle bisherigen Felder und die uebrigen REST-Antworten bleiben unveraendert.
`test-ergebnisse.sql` wird ausschliesslich in Testcontainers geladen.
V6 wurde nach erfolgreicher Pruefung regulaer mit dem sicheren Entwicklungsstart
auf `inventur_dev` angewendet. Flyway-Historie, Spalten und Constraints wurden
verifiziert; die Ergebnistabelle ist leer. Bestehende Daten wurden weder geloescht
noch ueberschrieben. V1 bis V5 sind anhand der Dateipruefsummen unveraendert.
Der temporaer gestartete Entwicklungsserver wurde anschliessend beendet.

## Grundlage aus Lernschritt 14

V5 ergaenzt `artikel` und `zaehlposition`. Artikel enthalten UUID, Artikelnummer
und Bezeichnung; beide Texte sind verpflichtend und duerfen nicht leer sein.
Die globale Eindeutigkeit der Artikelnummer ist in den verfuegbaren
Architekturunterlagen nicht abschliessend festgelegt. Daher gibt es vorerst
keinen UNIQUE-Constraint auf Artikelnummern. Identitaet wird ueber die UUID
hergestellt; die Frage nach weiteren Identifikationsmerkmalen bleibt offen.

Positionen besitzen eigene UUIDs, verpflichtende Blatt- und Artikelbeziehungen
sowie eine positive Positionsnummer, die je Blatt eindeutig ist. Lagerort,
Ist-Menge und Mindestbestand sind optional; die beiden Mengen sind nichtnegative
INTEGER-Werte und werden in Java als nullable `Integer` abgebildet.
`ist_menge` ist hier ein Bestands-/Referenzwert aus den Positionsstammdaten,
kein neu erfasstes Zaehlergebnis. Zaehlergebnisse und Revisionen fehlen weiterhin.

`ArtikelEntity` und `ZaehlpositionEntity` werden durch zwei neue JPA-Repositories
geladen. Die Positionsbeziehungen zu Blatt und Artikel sind unidirektional,
`LAZY` und ohne Loeschkaskaden. Dieselben Artikel duerfen mehrfach verwendet
werden. Jede Position gehoert ueber ihr Blatt zu genau einer Sitzung.
Positionen einer neuen Runde erhalten neue UUIDs; eine automatische Anlage
von Runden oder Positionen ist hier nicht implementiert.

`GET /api/v1/sitzungen/{sitzungId}/blaetter/{blattId}` liefert ein
`ZaehlblattDetailResponse` mit den bisherigen Blattfeldern und `positionen`.
Jede `ZaehlpositionResponse` enthaelt ID, Positionsnummer, Lagerort, Ist-Menge,
Mindestbestand und einen verschachtelten `ArtikelResponse` mit ID, Artikelnummer
und Bezeichnung. Positionen sind numerisch aufsteigend sortiert.
Ein vorhandenes Blatt ohne Positionen liefert HTTP 200 mit `positionen: []`.
Unbekannte Blaetter oder falsche Sitzungszuordnung liefern HTTP 404;
ungueltige UUIDs in beiden Pfadparametern liefern HTTP 400.

`ZaehlblattDetailsService` prueft Blatt-ID und Sitzungs-ID gemeinsam in der
Repository-Abfrage und mappt die Daten innerhalb einer Lesetransaktion.
Ein EntityGraph laedt die benoetigten Artikel gemeinsam mit den Positionen.
JPA-Entities werden nicht direkt als JSON ausgegeben. Der Controller enthaelt
keine Demo-Daten. Mangels Authentifizierung und Berechtigungspruefung darf der
Endpunkt ausschliesslich lokal verwendet werden; vor externer Bereitstellung
muss er abgesichert werden.

Pruefergebnis: 45 Tests erfolgreich, davon 2 datenbankfreie HTTP-Tests und
43 PostgreSQL-Integrationstests; keine Fehler und keine uebersprungenen Tests.
Die 14 neuen Tests pruefen V5, Hibernate-Validierung, Entities, Constraints,
Rundenidentitaet und das genaue Detail-JSON einschliesslich Null- und Nullmengen.
Alle bisherigen API-Antworten und Tests bleiben erfolgreich. Die fiktiven
Artikel und Positionen in `test-positionen.sql` werden ausschliesslich in einer
isolierten Testcontainers-Datenbank geladen; es gibt keinen Entwicklungs-Seed.
V5 wurde nach den erfolgreichen Tests ueber das vorhandene sichere Startskript
regulaer auf `inventur_dev` angewendet. Flyway-Historie, Spaltentypen und Constraints
sind geprueft. Artikel- und Positionstabellen bleiben dort leer; bestehende Daten
wurden nicht geloescht oder ueberschrieben. V1 bis V4 sind anhand ihrer
Dateipruefsummen unveraendert. Der temporaer gestartete Server wurde beendet.

## Grundlage aus Lernschritt 13

V4 ergaenzt ausschliesslich `zaehlblatt`: UUID, verpflichtende Sitzung und
Blattnummer, Status, Statusversion sowie optionale Abschlusszeit, abschliessender
Benutzer und letzte Status-Request-ID. Blattnummern sind innerhalb einer Sitzung
eindeutig und duerfen in anderen Sitzungen erneut vorkommen.
Statuswerte sind `OFFEN` / `ABGESCHLOSSEN`, Versionen mindestens 1.
Leere oder nur aus Leerzeichen bestehende Blattnummern werden abgelehnt.

`ZaehlblattEntity` besitzt unidirektionale `@ManyToOne(fetch = LAZY)`-Beziehungen
zur Sitzung und optional zum abschliessenden Benutzer. Es gibt keine
Loeschkaskaden und keine neuen bidirektionalen Beziehungen in bestehenden Entities.
`letzter_status_request_id` ist vorerst nur eine nullable UUID ohne Fremdschluessel.
Die Beziehung zu `sync_request` wird nach Einfuehrung dieser Tabelle in einer
eigenen Flyway-Migration ergaenzt; Idempotenz ist hier noch nicht implementiert.

`GET /api/v1/sitzungen/{sitzungId}/blaetter` verwendet Controller, Leseservice,
Repository und `ZaehlblattResponse`. Die DTO-Felder sind `id`, `sitzungId`,
`blattnummer`, `status`, `statusVersion`, `abgeschlossenAm`.
Vorhandene Sitzung ohne Blaetter: HTTP 200 mit `[]`; unbekannte Sitzung: HTTP 404;
ungueltige UUID: HTTP 400. Es werden ausschliesslich gespeicherte Daten gelesen.

Die Sortierung ist derzeit eine PostgreSQL-Textsortierung nach Blattnummer.
Fuer die Beispiele `1`, `2`, `10` ergibt sich `1`, `10`, `2`.
Die fachliche Sortierreihenfolge bleibt offen; das Nummerierungsmodell wurde
nicht geaendert. Blattstatus und Sitzungsstatus werden getrennt gespeichert.
Es gibt noch keine Abschluss-, Wiedereroeffnungs- oder Synchronisierungslogik.

Auch dieser Endpunkt darf mangels Authentifizierung und Berechtigungspruefung
nur lokal verwendet werden und muss vor einer externen Bereitstellung abgesichert
werden. Benutzer- und Request-Beziehungen werden nicht direkt als JSON ausgegeben.

Pruefergebnis: 31 Tests erfolgreich (2 datenbankfreie HTTP-Tests und 29 isolierte
PostgreSQL-Integrationstests), keine Fehler und keine uebersprungenen Tests.
Alle bisherigen API-Antworten bleiben unveraendert. V1 bis V3 wurden nicht geaendert.
Die neuen fiktiven Testdaten und ihre Bereinigung werden ausschliesslich innerhalb
der Testcontainers-Datenbanken verwendet; es gibt keinen neuen Entwicklungs-Seed.
V4 wurde ueber den vorhandenen sicheren Entwicklungsstart auf `inventur_dev`
angewendet. Flyway-Historie, Spalten und Constraints sind geprueft; Sitzungs- und
Blatttabelle sind dort weiterhin leer. Es wurden keine bestehenden Daten
geloescht oder ueberschrieben. Der fuer die Pruefung gestartete Server ist beendet.

## Grundlage aus Lernschritt 12

`GET /api/v1/zaehlerlisten/{listeId}/sitzungen` liest vorhandene Sitzungen aus
PostgreSQL, nach Zaehltag und Rundennummer aufsteigend sortiert.

- `dto/ZaehlsitzungResponse.java`: API-Record mit IDs, Datum, Runde, Status,
  Statusversion und Zeitpunkten; `abgeschlossenAm` darf `null` sein.
- `service/ZaehlsitzungenService.java`: prueft die Listenexistenz und mappt
  Entities innerhalb einer Lesetransaktion auf DTOs.
- `controller/ZaehlsitzungenController.java`: liest die Listen-UUID aus dem Pfad.
- Vorhandene Liste ohne Sitzungen: HTTP 200 mit `[]`.
- Unbekannte Listen-ID: HTTP 404. Ungueltiger UUID-Pfad: HTTP 400.

Es gibt noch keine Authentifizierung oder benutzerbezogene Berechtigungspruefung.
Dieser Endpunkt darf daher ausschliesslich im lokalen Entwicklungsbetrieb
verwendet werden. Vor einer externen Bereitstellung muss der Zugriff auf die
jeweils berechtigten Benutzer beschraenkt werden.

V3 wurde mit der vorhandenen sicheren Startkonfiguration regulaer auf
`inventur_dev` angewendet. Flyway-Historie und Sitzungsschema wurden geprueft;
es wurden keine fachlichen Sitzungsdaten in der Entwicklungsdatenbank angelegt.
V1, V2 und V3 bleiben unveraendert.

Die HTTP-Integrationstests verwenden ausschliesslich die isolierte
Testcontainers-Datenbank `inventur_test`. `test-sitzungen.sql` wird nur fuer den
zugehoerigen Test geladen und danach aufgeraeumt. Die bisherigen Endpunkte
bleiben unveraendert; es gibt keine Start- oder Abschlussfunktion.
Pruefergebnis: 21 Tests erfolgreich, davon 19 PostgreSQL-Integrationstests
und 2 datenbankfreie HTTP-Tests; keine Fehler und keine uebersprungenen Tests.

## Grundlage aus Lernschritt 11

V3 ergaenzt `zaehlsitzung`. V1 und V2 bleiben unveraendert.
Eine Sitzung gehoert ueber eine unidirektionale `@ManyToOne(fetch = LAZY)`-
Beziehung zu genau einer Zaehlerliste. Es gibt keine Loeschkaskade.

- `V3__create_zaehlsitzung.sql`: UUID, Listenzuordnung, Zaehltag, positive
  Rundennummer, Status, positive Statusversion und Start-/Abschlusszeitpunkte.
- `ZaehlsitzungEntity` und `ZaehlsitzungStatus`: JPA-Abbildung mit `LocalDate`,
  `Instant` und den expliziten Textwerten `OFFEN` / `ABGESCHLOSSEN`.
- `ZaehlsitzungenRepository`: Sitzungen einer Liste nach Zaehltag und Runde
  aufsteigend lesen. Noch keine fachliche Start- oder Abschlussfunktion.

Der UNIQUE-Constraint auf `(zaehlerliste_id, zaehltag, runde_nr)` verhindert
doppelte Runden. Ein partieller eindeutiger Index mit `WHERE status = 'OFFEN'`
erlaubt hoechstens eine offene Sitzung je Liste, auch ueber mehrere Tage.
Abgeschlossene Sitzungen bleiben gespeichert. Die Statusversion ist noch kein
automatisch verwaltetes JPA-`@Version`-Feld.

Pruefergebnis: 17 Tests erfolgreich (2 datenbankfreie HTTP-Tests und 15 isolierte
PostgreSQL-Integrationstests). Flyway V1 bis V3 und Hibernate-Schemavalidierung
wurden in der Testcontainers-Datenbank geprueft. Beide REST-Antworten bleiben
unveraendert. Es gibt keinen neuen Sitzungs-Endpunkt.

Die bestehende Entwicklungsdatenbank wurde in Lernschritt 11 nur gelesen.
Die damals noch ausstehende Anwendung von V3 erfolgte in Lernschritt 12 mit
`scripts/start-dev.ps1`; es werden keine Zaehl-Datensaetze automatisch angelegt.

## Grundlage aus Lernschritt 10

V2 ergaenzt die Tabelle `benutzer` und eine optionale Zuweisung an `zaehlerliste`.
V1 bleibt unveraendert. Es gibt weiterhin nur die beiden bisherigen Endpunkte
und dieselben API-DTO-Felder. Es wurden keine Anmeldung oder Benutzerverwaltung gebaut.

- `V2__create_benutzer_and_zuweisung.sql`: Benutzer, Rollenbedingung,
  Fremdschluessel und nichtnegative Zuweisungsversion mit Standardwert 0.
- `BenutzerEntity` und `BenutzerRolle`: Benutzer-Datensatz und Textwerte
  `ZAEHLER` / `ADMIN`; keine Anmeldedaten.
- `ZaehlerlisteEntity.aktuellerZaehler`: unidirektionale, nullable
  `@ManyToOne(fetch = LAZY)`-Beziehung ohne Loeschkaskade.
- `zuweisungVersion`: fachliche Version fuer spaetere Zuweisungspruefungen;
  noch keine automatische Neuzuweisungslogik.

Der Service mappt weiterhin nur ID, Code und Bezeichnung innerhalb seiner
Lese-Transaktion. Benutzerbeziehungen werden nicht direkt als JSON ausgegeben.
Der Fremdschluessel prueft die Existenz des Benutzers; Rollen-, Aktivitaets-
und Berechtigungspruefungen fuer echte Zuweisungen folgen spaeter im Service.

Der ausdrueckliche Benutzer-Seed kann nach V2 im Ordner `backend` ausgefuehrt werden:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\seed-dev.ps1 -Benutzer
```

Er verwendet nur `inventur_dev`, ueberschreibt keine Benutzer und ordnet nur die
beiden noch unzugewiesenen Demo-Listen mit Version 0 dem Demo-Zaehler A zu.
Eine Wiederholung aendert vorhandene Zuweisungen nicht.

Pruefergebnis: 2 datenbankfreie Tests und 8 isolierte PostgreSQL-Tests erfolgreich.
V1-SHA256 und Flyway-Pruefsumme unveraendert. V2 und Demo-Seed erfolgreich in
`inventur_dev`; bei Seed-Wiederholung 0 neue Benutzer und 0 Zuordnungsaenderungen.
Beide REST-Antworten unveraendert. Testserver nach der Pruefung gestoppt.

## Datenbankgrundlage aus Lernschritt 9

`GET /api/v1/zaehlerlisten` liest jetzt aus PostgreSQL. Entity und API-Record
sind getrennt. `GET /api/v1/health` bleibt unveraendert.

- `entity/ZaehlerlisteEntity.java`: Abbildung der ersten fachlichen Tabelle.
- `repository/ZaehlerlistenRepository.java`: Spring-Data-JPA-Interface.
- `service/ZaehlerlistenService.java`: Entity-zu-DTO-Abbildung, nach Code sortiert.
- `src/main/resources/db/migration/V1__create_zaehlerliste.sql`: Schema ohne Seed-Daten.
- `scripts/start-dev.ps1`: entschluesselt das vorhandene DPAPI-Passwort nur im
  Speicher und uebergibt es per Prozess-Umgebungsvariable an das Backend.
- `scripts/seed-dev.ps1` und `dev/seed-demo.sql`: ausdruecklicher, wiederholbarer
  Demo-Seed nur im festen Entwicklungscontainer und in `inventur_dev`.
- `PostgresIntegrationTests.java`: wegwerfbare Testdatenbank `inventur_test`.

Flyway verwaltet das Schema. Hibernate prueft es mit `ddl-auto=validate` und
legt keine Tabellen an. Flyway fuehrt zusaetzlich seine technische Tabelle
`flyway_schema_history`; das ist keine weitere Inventurtabelle.

Es gibt keine Zugangsdaten in den Anwendungs-Properties. Andere Umgebungen
muessen `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` und
`SPRING_DATASOURCE_PASSWORD` extern bereitstellen. Ohne eine konfigurierte
Datenbank startet die vollstaendige Anwendung nicht mehr.

## Dateien

- `pom.xml`: Java-Version, Abhaengigkeiten und Build-Konfiguration.
- `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`: Maven Wrapper.
- `InventurBackendApplication.java`: Einstiegspunkt und Spring-Boot-Konfiguration.
- `controller/HealthController.java`: GET `/api/v1/health`.
- `InventurBackendApplicationTests.java`: datenbankfreie Controller-Tests mit MockMvc.
- `src/main/resources/application.properties`: Anwendungsname und sichere Schema-/Logging-Einstellungen, ohne Zugangsdaten.
- `.gitignore`: schliesst Build-Ergebnisse, lokale Caches und Protokolle aus.

## Starten unter PowerShell

Im Projektordner `Zaehler_App`:

```powershell
cd backend
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-dev.ps1
```

`JAVA_HOME` gilt nur fuer diese PowerShell-Sitzung. Es wird keine Software installiert
und keine Android-Gradle-Konfiguration geaendert. Der Maven Wrapper laedt Maven und
die Projektabhaengigkeiten beim ersten Aufruf herunter.

Server stoppen: `Strg+C` im Terminal des Servers.

Die ExecutionPolicy-Ausnahme gilt nur fuer diesen PowerShell-Prozess, weil
dieser Rechner die Ausfuehrung von Skriptdateien standardmaessig blockiert.
Das Startskript stellt seine vorherigen Umgebungsvariablen beim Beenden wieder her.
Das vorhandene Passwort wird nicht veraendert oder neu erzeugt.

Nach der ersten Migration in einem zweiten Terminal im Ordner `backend`:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\seed-dev.ps1
```

Der Seed ueberschreibt keine Werte und loescht keine Daten. Bei abweichenden
Datensaetzen wird die Transaktion abgebrochen. Produktionsmigrationen enthalten
keine Demo-Daten. Der Listenendpunkt ist vor diesem ausdruecklichen Seed leer.

## Health pruefen

Im Browser: <http://localhost:8080/api/v1/health>

In einem zweiten PowerShell-Terminal:

```powershell
Invoke-RestMethod -Uri 'http://localhost:8080/api/v1/health' | ConvertTo-Json
```

Erwartete Antwort:

```json
{"status":"UP"}
```

## Automatischen Test ausfuehren

Im Ordner `backend`, ohne Entwicklungsdatenbank-Zugangsdaten:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:MAVEN_USER_HOME = Join-Path $PWD.Path '.cache\maven'
$truststore = Join-Path $PWD.Path '.cache\windows-truststore.p12'
$env:MAVEN_OPTS = '-Djavax.net.ssl.trustStore="' + $truststore + '" -Djavax.net.ssl.trustStoreType=PKCS12 -Djavax.net.ssl.trustStorePassword=changeit'
.\mvnw.cmd '-Dmaven.repo.local=.cache/repository' test
```

Fuer die Integrationstests muss Docker Desktop laufen. Falls die automatische
Docker-Erkennung nicht funktioniert, in diesem Terminal setzen:

```powershell
$env:DOCKER_HOST = 'npipe:////./pipe/dockerDesktopLinuxEngine'
```

Nur die zwei datenbankfreien Tests:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.cache/repository' '-Dtest=InventurBackendApplicationTests' test
```

Nur die acht PostgreSQL-Integrationstests:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.cache/repository' '-Dtest=PostgresIntegrationTests' test
```

Die Integrationstests beziehen ihre Verbindung ausschliesslich ueber
`@ServiceConnection` vom eigenen Testcontainer mit zufaelligem Passwort und
freiem Port. Sie verwenden nicht `inventur_dev`. Der temporaere Container wird
nach dem Lauf entfernt. Test-Seeds liegen nur unter `src/test/resources`.

## Zertifikatsproblem in der lokalen Entwicklungsumgebung

Beim ersten Maven-Aufruf trat `PKIX path building failed` auf. Fuer die lokale
Pruefung wurde eine Kopie der bereits von Windows vertrauten Root-Zertifikate in
`.cache/windows-truststore.p12` erstellt. Die TLS-Pruefung bleibt aktiv; die
Windows- und JDK-Zertifikatsspeicher bleiben unveraendert. Die Kopie ist ignoriert
und gehoert nicht zum veroeffentlichten Projekt.

Falls der Zertifikatsfehler auf diesem Rechner erneut auftritt, vor dem Maven-Aufruf:

```powershell
$truststore = Join-Path $PWD.Path '.cache\windows-truststore.p12'
$env:MAVEN_OPTS = '-Djavax.net.ssl.trustStore="' + $truststore + '" -Djavax.net.ssl.trustStoreType=PKCS12 -Djavax.net.ssl.trustStorePassword=changeit'
```

Der Truststore enthaelt oeffentliche Zertifikate und keine privaten Schluessel.
Auf einem anderen Rechner zuerst den regulaeren Maven-Aufruf verwenden.

## Lernnotizen

- **Spring Boot** konfiguriert eine Spring-Anwendung und den eingebetteten Webserver.
- **pom.xml** ist der Bauplan fuer Maven; Versionsverwaltung kommt vom Spring-Boot-Parent.
- **@SpringBootApplication** aktiviert Konfiguration, automatische Konfiguration
  und die Suche nach Komponenten unter dem Anwendungspaket.
- **@RestController** macht eine Klasse zum Webcontroller; Rueckgabewerte werden
  als Antwortinhalt geschrieben.
- **@GetMapping** ordnet eine Methode einem HTTP-GET-Pfad zu.
- Der Record aus `health()` wird durch die JSON-Verarbeitung von Spring Web in JSON
  umgewandelt. Eine HTML-Seite ist dafuer nicht erforderlich.

Der Health-Endpunkt bestaetigt
die Erreichbarkeit der Anwendung, nicht die Verfuegbarkeit einer Datenbank.

## Historisches Ergebnis aus Lernschritt 1

- Java: vorhandenes JDK 21.0.10 aus Android Studio.
- Spring Boot: 4.1.1; Maven Wrapper: 3.3.4 mit Maven 3.10.0.
- Automatischer HTTP-Test: 1 Test, 0 Fehler, 0 fehlgeschlagene Assertions.
- Regulaerer Start auf Port 8080: erfolgreich.
- GET `http://localhost:8080/api/v1/health`: HTTP 200,
  Content-Type `application/json`, Body `{"status":"UP"}`.
- Testserver nach der Pruefung wieder gestoppt.

## Ergebnis aus Lernschritt 9

- 2 datenbankfreie Controller-Tests und 4 isolierte PostgreSQL-Integrationstests erfolgreich.
- Flyway V1 angewendet; Hibernate-Schemaueberpruefung erfolgreich.
- `inventur_dev` enthaelt genau 2 Demo-Eintraege in `zaehlerliste` sowie Flyways technische Historientabelle.
- Seed wiederholt: keine weiteren Datensaetze eingefuegt.
- Beide REST-Endpunkte auf Port 8080 erfolgreich geprueft.
- Erneuter Backend-Start: Migration bereits aktuell, keine erneute Schemaanlage.
- Backend-Testserver gestoppt; Inventur-Entwicklungscontainer bleibt healthy.
- Die wegwerfbaren Testcontainer wurden entfernt; bestehende fremde Container blieben unveraendert.
