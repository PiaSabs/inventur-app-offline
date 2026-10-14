-- Nur fiktive Ergebnisse in der isolierten Testcontainers-Datenbank.
-- Position 840...001 besitzt absichtlich keine Ergebniszeile.
INSERT INTO zaehlergebnis
    (id, zaehlposition_id, zaehlmenge, anmerkung, revision, geaendert_von, geaendert_am) VALUES
    ('85000000-0000-4000-8000-000000000001', '84000000-0000-4000-8000-000000000002',
     0, 'Demo: leer gezaehlt', 3, '50000000-0000-4000-8000-000000000002', '2026-10-13T09:00:00Z'),
    ('85000000-0000-4000-8000-000000000002', '84000000-0000-4000-8000-000000000003',
     12, NULL, 2, NULL, '2026-10-13T10:00:00Z');
