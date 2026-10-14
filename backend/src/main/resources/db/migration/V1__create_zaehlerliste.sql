CREATE TABLE zaehlerliste (
    id uuid PRIMARY KEY,
    code text NOT NULL,
    bezeichnung text NOT NULL,
    CONSTRAINT uq_zaehlerliste_code UNIQUE (code),
    CONSTRAINT ck_zaehlerliste_code_not_blank CHECK (btrim(code) <> ''),
    CONSTRAINT ck_zaehlerliste_bezeichnung_not_blank CHECK (btrim(bezeichnung) <> '')
);
