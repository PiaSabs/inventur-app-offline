-- Nur der eigene Testcontainers-Graph; zyklischen Blatt-Request-FK vor Cleanup loesen.
UPDATE zaehlblatt SET letzter_status_request_id=NULL
WHERE zaehlsitzung_id IN ('93000000-0000-4000-8000-000000000001','93000000-0000-4000-8000-000000000002');
