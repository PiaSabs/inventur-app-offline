INSERT INTO zaehlerliste (id, code, bezeichnung) VALUES
    ('10000000-0000-4000-8000-000000000001', 'DEMO-01', 'Demo-Zaehlerliste Sonnenlager'),
    ('10000000-0000-4000-8000-000000000002', 'DEMO-02', 'Demo-Zaehlerliste Mondlager')
ON CONFLICT DO NOTHING;

INSERT INTO benutzer (id, anzeigename, rolle, aktiv) VALUES
    ('50000000-0000-4000-8000-000000000001', 'Demo-Zaehler A', 'ZAEHLER', true),
    ('50000000-0000-4000-8000-000000000002', 'Demo-Zaehler B', 'ZAEHLER', true),
    ('50000000-0000-4000-8000-000000000003', 'Demo-Administrator', 'ADMIN', true)
ON CONFLICT DO NOTHING;

UPDATE zaehlerliste
SET aktueller_zaehler_id = '50000000-0000-4000-8000-000000000001', zuweisung_version = 1
WHERE id IN ('10000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000002')
    AND aktueller_zaehler_id IS NULL AND zuweisung_version = 0;
