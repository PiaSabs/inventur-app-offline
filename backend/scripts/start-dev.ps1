$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
$passwordFile = Join-Path $backendRoot '.cache\inventur-postgres-dev-password.dpapi'
if (-not (Test-Path -LiteralPath $passwordFile)) {
    throw 'Die vorhandene DPAPI-Passwortdatei fehlt. Es wird kein neues Passwort erzeugt.'
}

$names = @('JAVA_HOME', 'MAVEN_USER_HOME', 'MAVEN_OPTS', 'SPRING_DATASOURCE_URL',
    'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD', 'SPRING_PROFILES_ACTIVE',
    'DEBUG', 'TRACE', 'LOGGING_LEVEL_ROOT')
$previous = @{}
foreach ($name in $names) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$secretBytes = $null
Push-Location $backendRoot
try {
    Add-Type -AssemblyName System.Security
    $secretBytes = [Security.Cryptography.ProtectedData]::Unprotect(
        [IO.File]::ReadAllBytes($passwordFile), $null,
        [Security.Cryptography.DataProtectionScope]::CurrentUser)
    if ($secretBytes.Length -eq 0) { throw 'Die Passwortdatei ist ungueltig.' }

    if (-not $env:JAVA_HOME) {
        $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
    }
    $env:MAVEN_USER_HOME = Join-Path $backendRoot '.cache\maven'
    $truststore = Join-Path $backendRoot '.cache\windows-truststore.p12'
    if (Test-Path -LiteralPath $truststore) {
        $env:MAVEN_OPTS = '-Djavax.net.ssl.trustStore="' + $truststore +
            '" -Djavax.net.ssl.trustStoreType=PKCS12 -Djavax.net.ssl.trustStorePassword=changeit'
    }
    $env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:5433/inventur_dev'
    $env:SPRING_DATASOURCE_USERNAME = 'inventur_dev'
    $env:SPRING_DATASOURCE_PASSWORD = [Text.Encoding]::UTF8.GetString($secretBytes)
    $env:SPRING_PROFILES_ACTIVE = 'dev'
    $env:DEBUG = 'false'
    $env:TRACE = 'false'
    $env:LOGGING_LEVEL_ROOT = 'INFO'

    & .\mvnw.cmd '-Dmaven.repo.local=.cache/repository' --batch-mode --no-transfer-progress spring-boot:run
    if ($LASTEXITCODE -ne 0) { throw 'Backend-Start fehlgeschlagen; Zugangsdaten werden nicht ausgegeben.' }
} finally {
    if ($null -ne $secretBytes) { [Array]::Clear($secretBytes, 0, $secretBytes.Length) }
    foreach ($name in $names) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
    }
    Pop-Location
}
