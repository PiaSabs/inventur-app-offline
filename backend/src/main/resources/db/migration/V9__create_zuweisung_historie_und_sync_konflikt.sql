-- Keine Bestandsdaten aendern; nur Schema und technische Referenzschluessel.
ALTER TABLE sync_request ADD CONSTRAINT uq_sync_request_liste UNIQUE (request_id, zaehlerliste_id);

CREATE TABLE zuweisung_historie (
    id uuid PRIMARY KEY,
    zaehlerliste_id uuid NOT NULL,
    vorheriger_zaehler_id uuid,
    neuer_zaehler_id uuid,
    version_vorher bigint NOT NULL,
    version_nachher bigint NOT NULL,
    verantwortlicher_benutzer_id uuid NOT NULL,
    geaendert_am timestamp with time zone NOT NULL DEFAULT clock_timestamp(),
    sync_request_id uuid,
    CONSTRAINT fk_zuweisung_historie_liste FOREIGN KEY (zaehlerliste_id) REFERENCES zaehlerliste (id),
    CONSTRAINT fk_zuweisung_historie_vorher FOREIGN KEY (vorheriger_zaehler_id) REFERENCES benutzer (id),
    CONSTRAINT fk_zuweisung_historie_nachher FOREIGN KEY (neuer_zaehler_id) REFERENCES benutzer (id),
    CONSTRAINT fk_zuweisung_historie_verantwortlicher FOREIGN KEY (verantwortlicher_benutzer_id) REFERENCES benutzer (id),
    CONSTRAINT fk_zuweisung_historie_request_liste FOREIGN KEY (sync_request_id, zaehlerliste_id)
        REFERENCES sync_request (request_id, zaehlerliste_id),
    CONSTRAINT uq_zuweisung_historie_liste_version UNIQUE (zaehlerliste_id, version_nachher),
    CONSTRAINT ck_zuweisung_historie_version CHECK (version_vorher >= 0 AND version_nachher > version_vorher
        AND version_nachher - version_vorher = 1),
    CONSTRAINT ck_zuweisung_historie_aenderung CHECK (vorheriger_zaehler_id IS DISTINCT FROM neuer_zaehler_id)
);

CREATE TABLE sync_konflikt (
    id uuid PRIMARY KEY,
    -- Fachliche Referenz, absichtlich KEIN FK auf sync_request.
    urspruengliche_request_id uuid NOT NULL,
    urheber_id uuid NOT NULL,
    -- Ausschliesslich aufgeloeste, gepruefte Ressourcen. Original-IDs stehen im kanonischen Inhalt.
    zaehlerliste_id uuid,
    zaehlsitzung_id uuid,
    zaehlblatt_id uuid,
    zaehlposition_id uuid,
    konfliktcode text NOT NULL,
    normalisierter_inhalt text NOT NULL,
    inhalt_format_version integer NOT NULL,
    inhalt_fingerprint text NOT NULL,
    basis_revision bigint NOT NULL,
    lokale_revision bigint NOT NULL,
    zuweisung_version bigint NOT NULL,
    erwartete_blatt_status_version bigint NOT NULL,
    erwartete_sitzung_status_version bigint NOT NULL,
    zaehlmenge integer,
    anmerkung text,
    erfasst_am timestamp with time zone NOT NULL DEFAULT clock_timestamp(),
    status text NOT NULL,
    CONSTRAINT fk_konflikt_urheber FOREIGN KEY (urheber_id) REFERENCES benutzer (id),
    CONSTRAINT fk_konflikt_liste FOREIGN KEY (zaehlerliste_id) REFERENCES zaehlerliste (id),
    CONSTRAINT fk_konflikt_sitzung_liste FOREIGN KEY (zaehlsitzung_id, zaehlerliste_id)
        REFERENCES zaehlsitzung (id, zaehlerliste_id),
    CONSTRAINT fk_konflikt_blatt_sitzung FOREIGN KEY (zaehlblatt_id, zaehlsitzung_id)
        REFERENCES zaehlblatt (id, zaehlsitzung_id),
    CONSTRAINT fk_konflikt_position_blatt FOREIGN KEY (zaehlposition_id, zaehlblatt_id)
        REFERENCES zaehlposition (id, zaehlblatt_id),
    CONSTRAINT ck_konflikt_hierarchie CHECK ((zaehlsitzung_id IS NULL OR zaehlerliste_id IS NOT NULL)
        AND (zaehlblatt_id IS NULL OR zaehlsitzung_id IS NOT NULL)
        AND (zaehlposition_id IS NULL OR zaehlblatt_id IS NOT NULL)),
    CONSTRAINT uq_konflikt_ursprung UNIQUE (urheber_id, urspruengliche_request_id, inhalt_format_version, inhalt_fingerprint),
    CONSTRAINT ck_konflikt_code CHECK (konfliktcode IN ('ZUWEISUNG_GEAENDERT', 'LISTE_NICHT_ZUGEWIESEN',
        'FALSCHE_ZUORDNUNG', 'SITZUNG_ABGESCHLOSSEN', 'BLATT_ABGESCHLOSSEN', 'STATUSVERSION_VERALTET',
        'ERGEBNISREVISION_VERALTET', 'ZIEL_NICHT_VORHANDEN', 'REQUEST_ID_REUSED')),
    CONSTRAINT ck_konflikt_status CHECK (status IN ('OFFEN', 'GELOEST', 'VERWORFEN')),
    CONSTRAINT ck_konflikt_inhalt CHECK (btrim(normalisierter_inhalt) <> ''),
    CONSTRAINT ck_konflikt_format CHECK (inhalt_format_version >= 1),
    CONSTRAINT ck_konflikt_fingerprint CHECK (inhalt_fingerprint ~ '^[0-9a-f]{64}$'
        AND inhalt_fingerprint = encode(sha256(convert_to(normalisierter_inhalt, 'UTF8')), 'hex')),
    CONSTRAINT ck_konflikt_revisionen CHECK (basis_revision >= 0 AND lokale_revision >= 0),
    CONSTRAINT ck_konflikt_versionen CHECK (zuweisung_version >= 0 AND erwartete_blatt_status_version >= 1
        AND erwartete_sitzung_status_version >= 1),
    CONSTRAINT ck_konflikt_menge CHECK (zaehlmenge >= 0)
);
CREATE INDEX ix_konflikt_liste_status ON sync_konflikt (zaehlerliste_id, status, erfasst_am, id);

