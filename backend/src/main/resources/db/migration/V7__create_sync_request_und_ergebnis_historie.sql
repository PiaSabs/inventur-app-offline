-- Technische Schluessel fuer Fremdschluessel auf die vollstaendige Zuordnung.
ALTER TABLE zaehlsitzung ADD CONSTRAINT uq_sitzung_id_liste UNIQUE (id, zaehlerliste_id);
ALTER TABLE zaehlblatt ADD CONSTRAINT uq_blatt_id_sitzung UNIQUE (id, zaehlsitzung_id);
ALTER TABLE zaehlposition ADD CONSTRAINT uq_position_id_blatt UNIQUE (id, zaehlblatt_id);

CREATE TABLE sync_request (
    request_id uuid PRIMARY KEY,
    urheber_id uuid NOT NULL,
    operation text NOT NULL,
    zaehlerliste_id uuid NOT NULL,
    zaehlsitzung_id uuid,
    zaehlblatt_id uuid,
    zaehlposition_id uuid,
    zuweisung_version bigint NOT NULL,
    erwartete_ergebnisrevision bigint,
    erwartete_blatt_status_version bigint,
    erwartete_sitzung_status_version bigint,
    normalisierter_inhalt text NOT NULL,
    inhalt_fingerprint text NOT NULL,
    inhalt_format_version integer NOT NULL,
    status text NOT NULL,
    entscheidungscode text,
    antwort_http_status integer,
    antwort_json text,
    eingegangen_am timestamp with time zone NOT NULL,
    verarbeitet_am timestamp with time zone,
    CONSTRAINT fk_sync_urheber FOREIGN KEY (urheber_id) REFERENCES benutzer (id),
    CONSTRAINT fk_sync_liste FOREIGN KEY (zaehlerliste_id) REFERENCES zaehlerliste (id),
    CONSTRAINT fk_sync_sitzung_liste FOREIGN KEY (zaehlsitzung_id, zaehlerliste_id)
        REFERENCES zaehlsitzung (id, zaehlerliste_id),
    CONSTRAINT fk_sync_blatt_sitzung FOREIGN KEY (zaehlblatt_id, zaehlsitzung_id)
        REFERENCES zaehlblatt (id, zaehlsitzung_id),
    CONSTRAINT fk_sync_position_blatt FOREIGN KEY (zaehlposition_id, zaehlblatt_id)
        REFERENCES zaehlposition (id, zaehlblatt_id),
    CONSTRAINT uq_sync_request_position_urheber UNIQUE (request_id, zaehlposition_id, urheber_id),
    CONSTRAINT uq_sync_request_blatt UNIQUE (request_id, zaehlblatt_id),
    CONSTRAINT ck_sync_operation CHECK (operation IN ('ERGEBNIS_SPEICHERN', 'BLATT_ABSCHLIESSEN',
        'BLATT_WIEDEROEFFNEN', 'RUNDE_STARTEN', 'LISTE_ZUWEISEN')),
    CONSTRAINT ck_sync_status CHECK (status IN ('EINGEGANGEN', 'AKZEPTIERT', 'ABGELEHNT')),
    CONSTRAINT ck_sync_zuweisung CHECK (zuweisung_version >= 0),
    CONSTRAINT ck_sync_revision CHECK (erwartete_ergebnisrevision >= 0),
    CONSTRAINT ck_sync_blatt_version CHECK (erwartete_blatt_status_version >= 1),
    CONSTRAINT ck_sync_sitzung_version CHECK (erwartete_sitzung_status_version >= 1),
    CONSTRAINT ck_sync_hierarchie CHECK ((zaehlblatt_id IS NULL OR zaehlsitzung_id IS NOT NULL)
        AND (zaehlposition_id IS NULL OR zaehlblatt_id IS NOT NULL)),
    CONSTRAINT ck_sync_inhalt CHECK (btrim(normalisierter_inhalt) <> ''),
    CONSTRAINT ck_sync_fingerprint CHECK (inhalt_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_sync_format_version CHECK (inhalt_format_version >= 1),
    CONSTRAINT ck_sync_http CHECK (antwort_http_status BETWEEN 200 AND 599),
    CONSTRAINT ck_sync_entscheidung CHECK (entscheidungscode IS NULL OR btrim(entscheidungscode) <> ''),
    CONSTRAINT ck_sync_antwort CHECK (antwort_json IS NULL OR btrim(antwort_json) <> ''),
    CONSTRAINT ck_sync_zeit CHECK (verarbeitet_am >= eingegangen_am),
    CONSTRAINT ck_sync_bearbeitung CHECK (
        (status = 'EINGEGANGEN' AND verarbeitet_am IS NULL AND entscheidungscode IS NULL
            AND antwort_http_status IS NULL AND antwort_json IS NULL)
        OR (status IN ('AKZEPTIERT', 'ABGELEHNT') AND verarbeitet_am IS NOT NULL
            AND entscheidungscode IS NOT NULL AND antwort_http_status IS NOT NULL AND antwort_json IS NOT NULL))
);

CREATE TABLE ergebnis_historie (
    id uuid PRIMARY KEY,
    zaehlposition_id uuid NOT NULL,
    request_id uuid NOT NULL,
    revision bigint NOT NULL,
    zaehlmenge integer,
    anmerkung text,
    benutzer_id uuid NOT NULL,
    geaendert_am timestamp with time zone NOT NULL,
    CONSTRAINT fk_historie_position FOREIGN KEY (zaehlposition_id) REFERENCES zaehlposition (id),
    CONSTRAINT fk_historie_benutzer FOREIGN KEY (benutzer_id) REFERENCES benutzer (id),
    CONSTRAINT fk_historie_request_kontext FOREIGN KEY (request_id, zaehlposition_id, benutzer_id)
        REFERENCES sync_request (request_id, zaehlposition_id, urheber_id),
    CONSTRAINT uq_historie_position_revision UNIQUE (zaehlposition_id, revision),
    CONSTRAINT uq_historie_request_position UNIQUE (request_id, zaehlposition_id),
    CONSTRAINT ck_historie_revision CHECK (revision >= 1),
    CONSTRAINT ck_historie_menge CHECK (zaehlmenge >= 0)
);

-- Blatt zuerst mit NULL-Verweis anlegen, Request speichern, danach den Verweis setzen.
-- Auch ein spaeter gesetzter Verweis muss zu genau diesem Blatt gehoeren.
ALTER TABLE zaehlblatt ADD CONSTRAINT fk_blatt_letzter_status_request
    FOREIGN KEY (letzter_status_request_id, id) REFERENCES sync_request (request_id, zaehlblatt_id);
