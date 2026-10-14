ALTER TABLE benutzer
    ADD COLUMN oidc_issuer text,
    ADD COLUMN oidc_subject text,
    ADD CONSTRAINT ck_benutzer_oidc_paar CHECK ((oidc_issuer IS NULL) = (oidc_subject IS NULL)),
    -- Whitespace einschliesslich Tabs, Zeilenumbruechen und Unicode-Leerzeichen.
    -- btrim dient nur zur Validierung; gespeicherte Identitaetswerte bleiben unveraendert.
    ADD CONSTRAINT ck_benutzer_oidc_issuer CHECK (oidc_issuer IS NULL OR btrim(oidc_issuer,
        U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\0085\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000') <> ''),
    ADD CONSTRAINT ck_benutzer_oidc_subject CHECK (oidc_subject IS NULL OR btrim(oidc_subject,
        U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\0085\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000') <> ''),
    ADD CONSTRAINT uq_benutzer_oidc_identitaet UNIQUE (oidc_issuer, oidc_subject);

-- Kein Seed und keine automatische Verknuepfung bestehender Benutzer.
-- PostgreSQL erlaubt mehrere NULL-Paare im regulaeren UNIQUE-Constraint.
