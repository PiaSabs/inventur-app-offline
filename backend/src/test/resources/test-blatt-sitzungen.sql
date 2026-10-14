-- Nur fuer die isolierte Testcontainers-Datenbank.
INSERT INTO zaehlsitzung
    (id, zaehlerliste_id, zaehltag, runde_nr, status, status_version, gestartet_am, abgeschlossen_am)
VALUES
    ('70000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001',
     '2026-10-13', 11, 'ABGESCHLOSSEN', 2, '2026-10-13T08:00:00Z', '2026-10-13T09:00:00Z'),
    ('70000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001',
     '2026-10-13', 12, 'ABGESCHLOSSEN', 2, '2026-10-13T10:00:00Z', '2026-10-13T11:00:00Z');
