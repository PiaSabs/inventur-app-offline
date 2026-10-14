param([switch]$Benutzer)

$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
$containerName = 'inventur-postgres-dev'
$state = & docker --context desktop-linux inspect $containerName --format '{{.State.Status}}|{{.State.Health.Status}}'
if ($LASTEXITCODE -ne 0 -or $state -ne 'running|healthy') {
    throw 'Der isolierte Inventur-Entwicklungscontainer ist nicht healthy.'
}

# Ausschliesslich dieser feste Container und diese Datenbank. Passwort nur im Container.
$seedFile = if ($Benutzer) { 'dev\seed-demo-benutzer.sql' } else { 'dev\seed-demo.sql' }
Get-Content -Raw -Encoding UTF8 (Join-Path $backendRoot $seedFile) |
    & docker --context desktop-linux exec -i --user postgres --env PGPASSFILE=/run/secrets/inventur-pgpass `
        $containerName psql -h 127.0.0.1 -U inventur_dev -d inventur_dev -w -v ON_ERROR_STOP=1 -f -
if ($LASTEXITCODE -ne 0) { throw 'Demo-Seed fehlgeschlagen; vorhandene Daten werden nicht ueberschrieben.' }
