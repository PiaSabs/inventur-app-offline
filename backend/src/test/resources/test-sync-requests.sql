-- Ausschliesslich fiktive, akzeptierte Requests fuer Strukturtests.
INSERT INTO sync_request (request_id, urheber_id, operation, zaehlerliste_id,
    zaehlsitzung_id, zaehlblatt_id, zaehlposition_id, zuweisung_version,
    erwartete_ergebnisrevision, normalisierter_inhalt, inhalt_fingerprint, inhalt_format_version,
    status, entscheidungscode, antwort_http_status, antwort_json, eingegangen_am, verarbeitet_am)
VALUES
    ('87000000-0000-4000-8000-000000000001', '50000000-0000-4000-8000-000000000002',
     'ERGEBNIS_SPEICHERN', '10000000-0000-4000-8000-000000000001',
     '70000000-0000-4000-8000-000000000001', '82000000-0000-4000-8000-000000000001',
     '84000000-0000-4000-8000-000000000001', 1, 0, '{"zaehlmenge":null}',
     encode(sha256(convert_to('{"zaehlmenge":null}', 'UTF8')), 'hex'), 1,
     'AKZEPTIERT', 'OK', 200, '{"revision":1}', '2026-10-14T08:00:00Z', '2026-10-14T08:00:01Z'),
    ('87000000-0000-4000-8000-000000000002', '50000000-0000-4000-8000-000000000002',
     'ERGEBNIS_SPEICHERN', '10000000-0000-4000-8000-000000000001',
     '70000000-0000-4000-8000-000000000001', '82000000-0000-4000-8000-000000000001',
     '84000000-0000-4000-8000-000000000001', 1, 1, '{"zaehlmenge":0}',
     encode(sha256(convert_to('{"zaehlmenge":0}', 'UTF8')), 'hex'), 1,
     'AKZEPTIERT', 'OK', 200, '{"revision":2}', '2026-10-14T08:01:00Z', '2026-10-14T08:01:01Z'),
    ('87000000-0000-4000-8000-000000000003', '50000000-0000-4000-8000-000000000002',
     'ERGEBNIS_SPEICHERN', '10000000-0000-4000-8000-000000000001',
     '70000000-0000-4000-8000-000000000001', '82000000-0000-4000-8000-000000000001',
     '84000000-0000-4000-8000-000000000001', 1, 2, '{"zaehlmenge":12}',
     encode(sha256(convert_to('{"zaehlmenge":12}', 'UTF8')), 'hex'), 1,
     'AKZEPTIERT', 'OK', 200, '{"revision":3}', '2026-10-14T08:02:00Z', '2026-10-14T08:02:01Z');
