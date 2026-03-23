[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

. "$PSScriptRoot\use-java17.ps1"

$env:MAVEN_OPTS = "-Xmx512m"

function Invoke-Step {
    param(
        [string]$Name,
        [string]$Command
    )

    Write-Host ""
    Write-Host "==> $Name" -ForegroundColor Cyan
    Invoke-Expression $Command
}

function Test-DockerAvailable {
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    return $null -ne $docker
}

Invoke-Step -Name "Hive/Doris JDBC adapter validation" -Command "mvn -q -pl data-audit-it -am test '-Dtest=SqliteDialectCliIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DforkCount=0'"
Invoke-Step -Name "Iceberg metadata reader unit validation" -Command "mvn -q -pl data-audit-connector-iceberg -am test '-Dtest=ReflectionIcebergMetadataReaderTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DforkCount=0'"
Invoke-Step -Name "Iceberg mixed JDBC CLI validation" -Command "mvn -q -pl data-audit-it -am test '-Dtest=IcebergMetadataCliIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DforkCount=0'"

if (Test-DockerAvailable) {
    Invoke-Step -Name "PostgreSQL real JDBC E2E" -Command "mvn -q -pl data-audit-it -am test '-Dtest=JdbcCliIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DforkCount=0'"
} else {
    Write-Host ""
    Write-Host "==> PostgreSQL real JDBC E2E" -ForegroundColor Cyan
    Write-Host "SKIPPED: docker is not available in the current environment." -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Second-layer verification completed." -ForegroundColor Green
