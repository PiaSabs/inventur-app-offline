-- Ausdruecklicher Entwicklungs-Seed; keine allgemeine Flyway-Migration.
BEGIN;

DO $$
BEGIN
    IF current_database() <> 'inventur_dev' OR current_user <> 'inventur_dev' THEN
        RAISE EXCEPTION 'Benutzer-Demo-Seed nur fuer inventur_dev erlaubt';
    END IF;
    IF (SELECT count(*) FROM zaehlerliste WHERE
           (id = '10000000-0000-4000-8000-000000000001' AND code = 'DEMO-01'
               AND bezeichnung = 'Demo-Zaehlerliste Sonnenlager') OR
           (id = '10000000-0000-4000-8000-000000000002' AND code = 'DEMO-02'
               AND bezeichnung = 'Demo-Zaehlerliste Mondlager')) <> 2 THEN
        RAISE EXCEPTION 'Erwartete Demo-Listen fehlen oder weichen ab';
    END IF;
END $$;

INSERT INTO benutzer (id, anzeigename, rolle, aktiv) VALUES
    ('50000000-0000-4000-8000-000000000001', 'Demo-Zaehler A', 'ZAEHLER', true),
    ('50000000-0000-4000-8000-000000000002', 'Demo-Zaehler B', 'ZAEHLER', true),
    ('50000000-0000-4000-8000-000000000003', 'Demo-Administrator', 'ADMIN', true)
ON CONFLICT DO NOTHING;

DO $$
BEGIN
    IF (SELECT count(*) FROM benutzer WHERE
            (id = '50000000-0000-4000-8000-000000000001' AND anzeigename = 'Demo-Zaehler A' AND rolle = 'ZAEHLER' AND aktiv) OR
            (id = '50000000-0000-4000-8000-000000000002' AND anzeigename = 'Demo-Zaehler B' AND rolle = 'ZAEHLER' AND aktiv) OR
            (id = '50000000-0000-4000-8000-000000000003' AND anzeigename = 'Demo-Administrator' AND rolle = 'ADMIN' AND aktiv)) <> 3 THEN
        RAISE EXCEPTION 'Unerwartete Demo-Benutzer; keine Werte werden ueberschrieben';
    END IF;
END $$;

UPDATE zaehlerliste
SET aktueller_zaehler_id = '50000000-0000-4000-8000-000000000001', zuweisung_version = 1
WHERE id IN ('10000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000002')
    AND aktueller_zaehler_id IS NULL AND zuweisung_version = 0;

COMMIT;
