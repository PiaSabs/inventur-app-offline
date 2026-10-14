CREATE TABLE artikel (
    id uuid PRIMARY KEY,
    artikelnummer text NOT NULL,
    bezeichnung text NOT NULL,
    CONSTRAINT ck_artikel_artikelnummer_not_blank CHECK (btrim(artikelnummer) <> ''),
    CONSTRAINT ck_artikel_bezeichnung_not_blank CHECK (btrim(bezeichnung) <> '')
);

-- Die fachliche Eindeutigkeit der Artikelnummer ist noch nicht geklaert.
-- Daher vorerst kein UNIQUE-Constraint auf artikelnummer; Identitaet ueber id.
CREATE TABLE zaehlposition (
    id uuid PRIMARY KEY,
    zaehlblatt_id uuid NOT NULL,
    artikel_id uuid NOT NULL,
    positionsnummer integer NOT NULL,
    lagerort text,
    ist_menge integer,
    mindestbestand integer,
    CONSTRAINT fk_zaehlposition_zaehlblatt FOREIGN KEY (zaehlblatt_id) REFERENCES zaehlblatt (id),
    CONSTRAINT fk_zaehlposition_artikel FOREIGN KEY (artikel_id) REFERENCES artikel (id),
    CONSTRAINT uq_zaehlposition_blatt_positionsnummer UNIQUE (zaehlblatt_id, positionsnummer),
    CONSTRAINT ck_zaehlposition_positionsnummer CHECK (positionsnummer > 0),
    CONSTRAINT ck_zaehlposition_ist_menge CHECK (ist_menge >= 0),
    CONSTRAINT ck_zaehlposition_mindestbestand CHECK (mindestbestand >= 0)
);
