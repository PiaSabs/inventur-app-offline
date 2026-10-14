-- Ausschliesslich manuell fuer die isolierte Inventur-Entwicklungsdatenbank.
-- Keine allgemeine Flyway-Migration und kein automatischer Produktions-Seed.
BEGIN;

DO $$
BEGIN
    IF current_database() <> 'inventur_dev' OR current_user <> 'inventur_dev' THEN
        RAISE EXCEPTION 'Demo-Seed nur fuer inventur_dev erlaubt';
    END IF;
END $$;

INSERT INTO zaehlerliste (id, code, bezeichnung) VALUES
    ('10000000-0000-4000-8000-000000000001', 'DEMO-01', 'Demo-Zaehlerliste Sonnenlager'),
    ('10000000-0000-4000-8000-000000000002', 'DEMO-02', 'Demo-Zaehlerliste Mondlager')
ON CONFLICT DO NOTHING;

DO $$
BEGIN
    IF (SELECT count(*) FROM zaehlerliste) <> 2 OR
       (SELECT count(*) FROM zaehlerliste WHERE
           (id = '10000000-0000-4000-8000-000000000001' AND code = 'DEMO-01'
               AND bezeichnung = 'Demo-Zaehlerliste Sonnenlager') OR
           (id = '10000000-0000-4000-8000-000000000002' AND code = 'DEMO-02'
               AND bezeichnung = 'Demo-Zaehlerliste Mondlager')) <> 2 THEN
        RAISE EXCEPTION 'Unerwartete Daten: Seed abgebrochen, keine bestehenden Werte ueberschrieben';
    END IF;
END $$;

COMMIT;
