CREATE TABLE zaehlsitzung (
    id uuid PRIMARY KEY,
    zaehlerliste_id uuid NOT NULL,
    zaehltag date NOT NULL,
    runde_nr integer NOT NULL,
    status text NOT NULL,
    status_version bigint NOT NULL,
    gestartet_am timestamp with time zone NOT NULL,
    abgeschlossen_am timestamp with time zone,
    CONSTRAINT fk_zaehlsitzung_zaehlerliste FOREIGN KEY (zaehlerliste_id) REFERENCES zaehlerliste (id),
    CONSTRAINT uq_zaehlsitzung_liste_tag_runde UNIQUE (zaehlerliste_id, zaehltag, runde_nr),
    CONSTRAINT ck_zaehlsitzung_status CHECK (status IN ('OFFEN', 'ABGESCHLOSSEN')),
    CONSTRAINT ck_zaehlsitzung_runde_nr CHECK (runde_nr > 0),
    CONSTRAINT ck_zaehlsitzung_status_version CHECK (status_version >= 1)
);

CREATE UNIQUE INDEX uq_zaehlsitzung_eine_offene_pro_liste
    ON zaehlsitzung (zaehlerliste_id)
    WHERE status = 'OFFEN';
