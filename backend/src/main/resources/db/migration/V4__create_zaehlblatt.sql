CREATE TABLE zaehlblatt (
    id uuid PRIMARY KEY,
    zaehlsitzung_id uuid NOT NULL,
    blattnummer text NOT NULL,
    status text NOT NULL,
    status_version bigint NOT NULL,
    abgeschlossen_am timestamp with time zone,
    geschlossen_von uuid,
    letzter_status_request_id uuid,
    CONSTRAINT fk_zaehlblatt_zaehlsitzung FOREIGN KEY (zaehlsitzung_id) REFERENCES zaehlsitzung (id),
    CONSTRAINT fk_zaehlblatt_geschlossen_von FOREIGN KEY (geschlossen_von) REFERENCES benutzer (id),
    CONSTRAINT uq_zaehlblatt_sitzung_blattnummer UNIQUE (zaehlsitzung_id, blattnummer),
    CONSTRAINT ck_zaehlblatt_blattnummer_not_blank CHECK (btrim(blattnummer) <> ''),
    CONSTRAINT ck_zaehlblatt_status CHECK (status IN ('OFFEN', 'ABGESCHLOSSEN')),
    CONSTRAINT ck_zaehlblatt_status_version CHECK (status_version >= 1)
);

-- sync_request existiert noch nicht. Der Fremdschluessel fuer
-- letzter_status_request_id wird spaeter in einer eigenen Migration ergaenzt.
