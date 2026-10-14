CREATE TABLE benutzer (
    id uuid PRIMARY KEY,
    anzeigename text NOT NULL,
    rolle text NOT NULL,
    aktiv boolean NOT NULL,
    CONSTRAINT ck_benutzer_anzeigename_not_blank CHECK (btrim(anzeigename) <> ''),
    CONSTRAINT ck_benutzer_rolle CHECK (rolle IN ('ZAEHLER', 'ADMIN'))
);

ALTER TABLE zaehlerliste
    ADD COLUMN aktueller_zaehler_id uuid,
    ADD COLUMN zuweisung_version bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_zaehlerliste_zuweisung_version CHECK (zuweisung_version >= 0),
    ADD CONSTRAINT fk_zaehlerliste_aktueller_zaehler
        FOREIGN KEY (aktueller_zaehler_id) REFERENCES benutzer (id);
