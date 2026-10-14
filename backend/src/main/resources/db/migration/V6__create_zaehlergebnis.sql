CREATE TABLE zaehlergebnis (
    id uuid PRIMARY KEY,
    zaehlposition_id uuid NOT NULL,
    zaehlmenge integer,
    anmerkung text,
    revision bigint NOT NULL,
    geaendert_von uuid,
    geaendert_am timestamp with time zone,
    CONSTRAINT uq_zaehlergebnis_position UNIQUE (zaehlposition_id),
    CONSTRAINT fk_zaehlergebnis_position FOREIGN KEY (zaehlposition_id) REFERENCES zaehlposition (id),
    CONSTRAINT fk_zaehlergebnis_geaendert_von FOREIGN KEY (geaendert_von) REFERENCES benutzer (id),
    CONSTRAINT ck_zaehlergebnis_zaehlmenge CHECK (zaehlmenge >= 0),
    CONSTRAINT ck_zaehlergebnis_revision CHECK (revision >= 0)
);

-- NULL-Menge bedeutet ungezaehlt; 0 ist eine gueltige gezaehlte Menge.
-- Fehlende Ergebniszeilen werden beim Lesen als logischer Ausgangsstand
-- (NULL-Menge, NULL-Anmerkung, Revision 0) behandelt, nicht automatisch angelegt.
