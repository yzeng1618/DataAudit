$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "use-java17.ps1")

$verifyRoot = Join-Path $repoRoot ".tmp\verify-local"
$classDir = Join-Path $verifyRoot "classes"
$jarPath = Join-Path $repoRoot "data-audit-cli\target\data-audit.jar"

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)]
        [string]$FilePath,
        [string[]]$Arguments = @(),
        [int[]]$AllowedExitCodes = @(0)
    )

    & $FilePath @Arguments
    if ($AllowedExitCodes -notcontains $LASTEXITCODE) {
        throw "Command failed: $FilePath $($Arguments -join ' ') (exit code: $LASTEXITCODE)"
    }
}

function Assert-Equal {
    param(
        $Actual,
        $Expected,
        [Parameter(Mandatory = $true)]
        [string]$Message
    )

    if ("$Actual" -ne "$Expected") {
        throw "$Message. Expected='$Expected', Actual='$Actual'"
    }
}

function Assert-True {
    param(
        [Parameter(Mandatory = $true)]
        [bool]$Condition,
        [Parameter(Mandatory = $true)]
        [string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

function Get-SqliteJdbcJar {
    $candidates = @(
        (Join-Path $env:USERPROFILE ".m2\repository\org\xerial\sqlite-jdbc"),
        "D:\workspace\mvn_repo\org\xerial\sqlite-jdbc"
    )
    foreach ($base in $candidates) {
        if (Test-Path $base) {
            $jar = Get-ChildItem $base -Recurse -Filter "sqlite-jdbc-*.jar" |
                Sort-Object FullName |
                Select-Object -Last 1
            if ($null -ne $jar) {
                return $jar.FullName
            }
        }
    }
    throw "sqlite-jdbc jar was not found in local Maven repositories."
}

function Get-Slf4jApiJar {
    $candidates = @(
        (Join-Path $env:USERPROFILE ".m2\repository\org\slf4j\slf4j-api"),
        "D:\workspace\mvn_repo\org\slf4j\slf4j-api"
    )
    foreach ($base in $candidates) {
        if (Test-Path $base) {
            $jar = Get-ChildItem $base -Recurse -Filter "slf4j-api-*.jar" |
                Sort-Object FullName |
                Select-Object -Last 1
            if ($null -ne $jar) {
                return $jar.FullName
            }
        }
    }
    throw "slf4j-api jar was not found in local Maven repositories."
}

function New-TaskYaml {
    param(
        [string]$Path,
        [string]$SourceDb,
        [string]$TargetDb,
        [string]$ReportDir,
        [string]$StatePath,
        [string]$Mode,
        [Nullable[long]]$EstimatedRows,
        [Nullable[long]]$MaxExactRows,
        [string]$SegmentColumn,
        [string]$BoundaryType = "job_finish",
        [string]$BoundaryReference = "latest",
        [bool]$IncludeKey = $true,
        [string]$DdlMode = "compatible"
    )

    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("task:")
    $lines.Add("  name: local_sqlite_verify")
    $lines.Add("  mode: post_check")
    $lines.Add("")
    $lines.Add("boundary:")
    $lines.Add("  type: $BoundaryType")
    $lines.Add("  reference: $BoundaryReference")
    $lines.Add("")
    $lines.Add("source:")
    $lines.Add("  type: jdbc")
    $lines.Add("  url: jdbc:sqlite:$SourceDb")
    $lines.Add("  table: orders")
    $lines.Add("  options:")
    $lines.Add("    dialect: postgres")
    $lines.Add("")
    $lines.Add("target:")
    $lines.Add("  type: jdbc")
    $lines.Add("  url: jdbc:sqlite:$TargetDb")
    $lines.Add("  table: orders")
    $lines.Add("  options:")
    $lines.Add("    dialect: postgres")
    $lines.Add("")
    if ($IncludeKey) {
        $lines.Add("object:")
        $lines.Add("  key:")
        $lines.Add("    - order_id")
        $lines.Add("")
    }
    $lines.Add("planner:")
    $lines.Add("  mode: $Mode")
    $lines.Add("  hints:")
    if ($null -ne $EstimatedRows) {
        $lines.Add("    estimated_rows: $EstimatedRows")
    }
    if ($null -ne $MaxExactRows) {
        $lines.Add("    max_exact_rows: $MaxExactRows")
    }
    if ($null -ne $SegmentColumn -and $SegmentColumn -ne "") {
        $lines.Add("    partition_keys:")
        $lines.Add("      - $SegmentColumn")
    }
    if ($null -ne $SegmentColumn -and $SegmentColumn -ne "") {
        $lines.Add("")
        $lines.Add("compare:")
        $lines.Add("  segment:")
        $lines.Add("    by:")
        $lines.Add("      - $SegmentColumn")
    }
    $lines.Add("")
    $lines.Add("ddl:")
    $lines.Add("  mode: $DdlMode")
    $lines.Add("")
    $lines.Add("output:")
    $lines.Add("  dir: $ReportDir")
    $lines.Add("")
    $lines.Add("state:")
    $lines.Add("  backend: sqlite")
    $lines.Add("  path: $StatePath")

    Set-Content -Path $Path -Value $lines -Encoding UTF8
}

function Invoke-Scenario {
    param(
        [string]$Scenario,
        [string]$PlannerMode,
        [Nullable[long]]$EstimatedRows,
        [Nullable[long]]$MaxExactRows,
        [string]$SegmentColumn,
        [int]$ExpectedCheckExitCode,
        [string]$ExpectedStatus,
        [string]$ExpectedObjectClass,
        [string]$ExpectedSelectedPath,
        [string]$ExpectedRootCause,
        [string]$ExpectedFirstSuspectSegment = "",
        [string]$BoundaryType = "job_finish",
        [string]$BoundaryReference = "latest",
        [bool]$IncludeKey = $true,
        [string]$DdlMode = "compatible",
        [int]$ExpectedPlanExitCode = 0
    )

    $scenarioRoot = Join-Path $verifyRoot $Scenario
    $sourceDb = Join-Path $scenarioRoot "source.db"
    $targetDb = Join-Path $scenarioRoot "target.db"
    $reportsDir = Join-Path $scenarioRoot "reports"
    $statePath = Join-Path $scenarioRoot "state.db"
    $taskFile = Join-Path $scenarioRoot "task.yaml"

    New-Item -ItemType Directory -Force -Path $scenarioRoot | Out-Null
    if (Test-Path $sourceDb) { Remove-Item $sourceDb -Force }
    if (Test-Path $targetDb) { Remove-Item $targetDb -Force }
    if (Test-Path $reportsDir) { Remove-Item $reportsDir -Recurse -Force }
    if (Test-Path $statePath) { Remove-Item $statePath -Force }

    Invoke-Native -FilePath "$env:JAVA_HOME\bin\java.exe" -Arguments @("-cp", "$sqliteJar;$slf4jApiJar;$classDir", "SampleSqliteSeeder", $Scenario, $sourceDb, $targetDb)

    New-TaskYaml -Path $taskFile `
        -SourceDb $sourceDb `
        -TargetDb $targetDb `
        -ReportDir $reportsDir `
        -StatePath $statePath `
        -Mode $PlannerMode `
        -EstimatedRows $EstimatedRows `
        -MaxExactRows $MaxExactRows `
        -SegmentColumn $SegmentColumn `
        -BoundaryType $BoundaryType `
        -BoundaryReference $BoundaryReference `
        -IncludeKey:$IncludeKey `
        -DdlMode $DdlMode

    Write-Host ""
    Write-Host "=== Scenario: $Scenario ==="
    Invoke-Native -FilePath "$env:JAVA_HOME\bin\java.exe" -Arguments @("-jar", $jarPath, "plan", "-f", $taskFile) -AllowedExitCodes @($ExpectedPlanExitCode)
    Invoke-Native -FilePath "$env:JAVA_HOME\bin\java.exe" -Arguments @("-jar", $jarPath, "check", "-f", $taskFile) -AllowedExitCodes @($ExpectedCheckExitCode)
    Invoke-Native -FilePath "$env:JAVA_HOME\bin\java.exe" -Arguments @("-jar", $jarPath, "report", "show", (Join-Path $reportsDir "report.json")) -AllowedExitCodes @($ExpectedCheckExitCode)

    $reportPath = Join-Path $reportsDir "report.json"
    $report = Get-Content $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json

    Assert-True -Condition (Test-Path $reportPath) -Message "report.json was not generated for $Scenario"
    Assert-True -Condition (Test-Path (Join-Path $reportsDir "report.html")) -Message "report.html was not generated for $Scenario"
    Assert-True -Condition (Test-Path (Join-Path $reportsDir "suspect_segments.csv")) -Message "suspect_segments.csv was not generated for $Scenario"
    Assert-True -Condition (Test-Path (Join-Path $reportsDir "row_diff_sample.csv")) -Message "row_diff_sample.csv was not generated for $Scenario"
    Assert-True -Condition (Test-Path $statePath) -Message "state.db was not generated for $Scenario"

    Assert-Equal -Actual $report.result.status -Expected $ExpectedStatus -Message "Unexpected report status for $Scenario"
    Assert-Equal -Actual $report.plan.object_class -Expected $ExpectedObjectClass -Message "Unexpected object class for $Scenario"
    Assert-Equal -Actual $report.plan.selected_path -Expected $ExpectedSelectedPath -Message "Unexpected selected path for $Scenario"
    Assert-Equal -Actual $report.result.root_cause -Expected $ExpectedRootCause -Message "Unexpected root cause for $Scenario"
    if ($ExpectedFirstSuspectSegment -ne "") {
        Assert-Equal -Actual $report.result.suspect_segments[0].segment_key -Expected $ExpectedFirstSuspectSegment -Message "Unexpected suspect segment for $Scenario"
    }
}

New-Item -ItemType Directory -Force -Path $verifyRoot | Out-Null
$sqliteJar = Get-SqliteJdbcJar
$slf4jApiJar = Get-Slf4jApiJar

if (-not (Test-Path $jarPath)) {
    Invoke-Native -FilePath "mvn" -Arguments @("-q", "-DskipTests", "clean", "package")
}

New-Item -ItemType Directory -Force -Path $classDir | Out-Null
Invoke-Native -FilePath "$env:JAVA_HOME\bin\javac.exe" -Arguments @("-cp", "$sqliteJar;$slf4jApiJar", "-d", $classDir, (Join-Path $PSScriptRoot "java\SampleSqliteSeeder.java"))

Invoke-Scenario -Scenario "consistent_small" -PlannerMode "auto" -EstimatedRows 2 -MaxExactRows 100 -SegmentColumn "" -ExpectedCheckExitCode 0 -ExpectedStatus "CONSISTENT" -ExpectedObjectClass "small_table_once" -ExpectedSelectedPath "schema -> exact diff" -ExpectedRootCause "consistent"
Invoke-Scenario -Scenario "small_diff" -PlannerMode "auto" -EstimatedRows 2 -MaxExactRows 100 -SegmentColumn "" -ExpectedCheckExitCode 1 -ExpectedStatus "DIFF_FOUND" -ExpectedObjectClass "small_table_once" -ExpectedSelectedPath "schema -> exact diff" -ExpectedRootCause "checksum_mismatch"
Invoke-Scenario -Scenario "keyless_multiset" -PlannerMode "auto" -EstimatedRows 3 -MaxExactRows 100 -SegmentColumn "" -ExpectedCheckExitCode 1 -ExpectedStatus "DIFF_FOUND" -ExpectedObjectClass "small_table_once" -ExpectedSelectedPath "schema -> exact diff" -ExpectedRootCause "checksum_mismatch" -IncludeKey:$false
Invoke-Scenario -Scenario "partition_mismatch" -PlannerMode "segment_first" -EstimatedRows 1000000 -MaxExactRows 100 -SegmentColumn "dt" -ExpectedCheckExitCode 1 -ExpectedStatus "DIFF_FOUND" -ExpectedObjectClass "partitioned_big_table" -ExpectedSelectedPath "schema -> summary -> segment -> diff" -ExpectedRootCause "checksum_mismatch" -ExpectedFirstSuspectSegment "dt=2026-03-10"
Invoke-Scenario -Scenario "schema_mismatch" -PlannerMode "auto" -EstimatedRows 2 -MaxExactRows 100 -SegmentColumn "" -ExpectedCheckExitCode 1 -ExpectedStatus "DIFF_FOUND" -ExpectedObjectClass "small_table_once" -ExpectedSelectedPath "schema -> exact diff" -ExpectedRootCause "schema_mismatch" -DdlMode "strict"
Invoke-Scenario -Scenario "unstable_snapshot_jdbc" -PlannerMode "auto" -EstimatedRows 2 -MaxExactRows 100 -SegmentColumn "" -ExpectedCheckExitCode 5 -ExpectedStatus "REFUSED" -ExpectedObjectClass "" -ExpectedSelectedPath "" -ExpectedRootCause "unstable_boundary" -BoundaryType "snapshot" -BoundaryReference "latest" -ExpectedPlanExitCode 5

Write-Host ""
Write-Host "Verification artifacts were written under $verifyRoot"
$global:LASTEXITCODE = 0
