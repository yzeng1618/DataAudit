$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot "use-java17.ps1")

$verifyRoot = Join-Path $repoRoot ".tmp\verify-second-layer"
$jarPath = Join-Path $repoRoot "data-audit-cli\target\data-audit.jar"
$classpathFile = Join-Path $verifyRoot "data-audit-it-test.classpath"

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

function Ensure-BuildArtifacts {
    New-Item -ItemType Directory -Force -Path $verifyRoot | Out-Null

    Invoke-Native -FilePath "mvn" -Arguments @("-q", "-DskipTests", "install")
    Invoke-Native -FilePath "mvn" -Arguments @(
        "-q",
        "-f", (Join-Path $repoRoot "data-audit-it\pom.xml"),
        "dependency:build-classpath",
        "-Dmdep.includeScope=test",
        "-Dmdep.outputFile=$classpathFile"
    )
}

function Get-ItClasspath {
    $dependencyClasspath = (Get-Content -Path $classpathFile -Raw -Encoding UTF8).Trim()
    $testClasses = Join-Path $repoRoot "data-audit-it\target\test-classes"
    $mainClasses = Join-Path $repoRoot "data-audit-it\target\classes"
    return "$testClasses;$mainClasses;$dependencyClasspath"
}

function New-JdbcTaskYaml {
    param(
        [string]$Path,
        [string]$SourceDb,
        [string]$TargetDb,
        [string]$ReportDir,
        [string]$StatePath,
        [string]$Dialect,
        [string]$PlannerMode,
        [Nullable[long]]$EstimatedRows,
        [Nullable[long]]$MaxExactRows,
        [string]$SegmentColumn
    )

    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("task:")
    $lines.Add("  name: second_layer_local_verify")
    $lines.Add("  mode: post_check")
    $lines.Add("")
    $lines.Add("boundary:")
    $lines.Add("  type: job_finish")
    $lines.Add("  reference: latest")
    $lines.Add("")
    $lines.Add("source:")
    $lines.Add("  type: jdbc")
    $lines.Add("  url: jdbc:sqlite:$SourceDb")
    $lines.Add("  table: orders")
    $lines.Add("  options:")
    $lines.Add("    dialect: $Dialect")
    $lines.Add("")
    $lines.Add("target:")
    $lines.Add("  type: jdbc")
    $lines.Add("  url: jdbc:sqlite:$TargetDb")
    $lines.Add("  table: orders")
    $lines.Add("  options:")
    $lines.Add("    dialect: $Dialect")
    $lines.Add("")
    $lines.Add("object:")
    $lines.Add("  key:")
    $lines.Add("    - order_id")
    $lines.Add("")
    $lines.Add("planner:")
    $lines.Add("  mode: $PlannerMode")
    $lines.Add("  hints:")
    if ($null -ne $EstimatedRows) {
        $lines.Add("    estimated_rows: $EstimatedRows")
    }
    if ($null -ne $MaxExactRows) {
        $lines.Add("    max_exact_rows: $MaxExactRows")
    }
    if ($SegmentColumn) {
        $lines.Add("    partition_keys:")
        $lines.Add("      - $SegmentColumn")
    }
    if ($SegmentColumn) {
        $lines.Add("")
        $lines.Add("compare:")
        $lines.Add("  segment:")
        $lines.Add("    by:")
        $lines.Add("      - $SegmentColumn")
    }
    $lines.Add("")
    $lines.Add("output:")
    $lines.Add("  dir: $ReportDir")
    $lines.Add("")
    $lines.Add("state:")
    $lines.Add("  backend: sqlite")
    $lines.Add("  path: $StatePath")

    Set-Content -Path $Path -Value $lines -Encoding UTF8
}

function New-IcebergTaskYaml {
    param(
        [string]$Path,
        [string]$SourceDb,
        [string]$TableLocation,
        [string]$ReportDir,
        [string]$StatePath
    )

    $lines = @(
        "task:",
        "  name: second_layer_iceberg_verify",
        "  mode: post_check",
        "",
        "boundary:",
        "  type: snapshot",
        "  reference: latest",
        "",
        "source:",
        "  type: jdbc",
        "  url: jdbc:sqlite:$SourceDb",
        "  table: orders",
        "  options:",
        "    dialect: postgres",
        "",
        "target:",
        "  type: iceberg",
        "  location: $TableLocation",
        "  table: orders",
        "",
        "planner:",
        "  mode: metadata_first",
        "  hints:",
        "    object_class: lakehouse_object",
        "    prefer_metadata: true",
        "",
        "output:",
        "  dir: $ReportDir",
        "",
        "state:",
        "  backend: sqlite",
        "  path: $StatePath"
    )

    Set-Content -Path $Path -Value $lines -Encoding UTF8
}

function Invoke-JdbcScenario {
    param(
        [string]$Scenario,
        [string]$Dialect,
        [string]$PlannerMode,
        [Nullable[long]]$EstimatedRows,
        [Nullable[long]]$MaxExactRows,
        [string]$SegmentColumn,
        [int]$ExpectedCheckExitCode,
        [string]$ExpectedStatus,
        [string]$ExpectedObjectClass,
        [string]$ExpectedSelectedPath,
        [string]$ExpectedRootCause,
        [string]$ExpectedFirstSuspectSegment = ""
    )

    $scenarioRoot = Join-Path $verifyRoot $Scenario
    $sourceDb = Join-Path $scenarioRoot "source.db"
    $targetDb = Join-Path $scenarioRoot "target.db"
    $reportsDir = Join-Path $scenarioRoot "reports"
    $statePath = Join-Path $scenarioRoot "state.db"
    $taskFile = Join-Path $scenarioRoot "task.yaml"

    New-Item -ItemType Directory -Force -Path $scenarioRoot | Out-Null
    if (Test-Path $reportsDir) { Remove-Item $reportsDir -Recurse -Force }
    if (Test-Path $statePath) { Remove-Item $statePath -Force }

    Invoke-Native -FilePath "$env:JAVA_HOME\bin\java.exe" -Arguments @("-cp", $itClasspath, "io.github.dataaudit.it.support.SecondLayerFixtureBuilder", $Scenario, $sourceDb, $targetDb)
    New-JdbcTaskYaml -Path $taskFile -SourceDb $sourceDb -TargetDb $targetDb -ReportDir $reportsDir -StatePath $statePath -Dialect $Dialect -PlannerMode $PlannerMode -EstimatedRows $EstimatedRows -MaxExactRows $MaxExactRows -SegmentColumn $SegmentColumn

    Write-Host ""
    Write-Host "=== Scenario: $Scenario ==="
    Invoke-Native -FilePath "$env:JAVA_HOME\bin\java.exe" -Arguments @("-jar", $jarPath, "plan", "-f", $taskFile)
    Invoke-Native -FilePath "$env:JAVA_HOME\bin\java.exe" -Arguments @("-jar", $jarPath, "check", "-f", $taskFile) -AllowedExitCodes @($ExpectedCheckExitCode)
    Invoke-Native -FilePath "$env:JAVA_HOME\bin\java.exe" -Arguments @("-jar", $jarPath, "report", "show", (Join-Path $reportsDir "report.json")) -AllowedExitCodes @($ExpectedCheckExitCode)

    Assert-ScenarioArtifacts -Scenario $Scenario -ReportsDir $reportsDir -StatePath $statePath
    Assert-ScenarioReport -Scenario $Scenario -ReportsDir $reportsDir -ExpectedStatus $ExpectedStatus -ExpectedObjectClass $ExpectedObjectClass -ExpectedSelectedPath $ExpectedSelectedPath -ExpectedRootCause $ExpectedRootCause -ExpectedFirstSuspectSegment $ExpectedFirstSuspectSegment
}

function Invoke-IcebergScenario {
    param(
        [string]$Scenario,
        [int]$ExpectedCheckExitCode,
        [string]$ExpectedStatus,
        [string]$ExpectedObjectClass,
        [string]$ExpectedSelectedPath,
        [string]$ExpectedRootCause,
        [string]$ExpectedFirstSuspectSegment
    )

    $scenarioRoot = Join-Path $verifyRoot $Scenario
    $sourceDb = Join-Path $scenarioRoot "source.db"
    $tableLocation = Join-Path $scenarioRoot "warehouse\orders"
    $reportsDir = Join-Path $scenarioRoot "reports"
    $statePath = Join-Path $scenarioRoot "state.db"
    $taskFile = Join-Path $scenarioRoot "task.yaml"

    New-Item -ItemType Directory -Force -Path $scenarioRoot | Out-Null
    if (Test-Path $reportsDir) { Remove-Item $reportsDir -Recurse -Force }
    if (Test-Path $statePath) { Remove-Item $statePath -Force }

    Invoke-Native -FilePath "$env:JAVA_HOME\bin\java.exe" -Arguments @("-cp", $itClasspath, "io.github.dataaudit.it.support.SecondLayerFixtureBuilder", $Scenario, $sourceDb, $tableLocation)
    New-IcebergTaskYaml -Path $taskFile -SourceDb $sourceDb -TableLocation $tableLocation -ReportDir $reportsDir -StatePath $statePath

    Write-Host ""
    Write-Host "=== Scenario: $Scenario ==="
    Invoke-Native -FilePath "$env:JAVA_HOME\bin\java.exe" -Arguments @("-jar", $jarPath, "plan", "-f", $taskFile)
    Invoke-Native -FilePath "$env:JAVA_HOME\bin\java.exe" -Arguments @("-jar", $jarPath, "check", "-f", $taskFile) -AllowedExitCodes @($ExpectedCheckExitCode)
    Invoke-Native -FilePath "$env:JAVA_HOME\bin\java.exe" -Arguments @("-jar", $jarPath, "report", "show", (Join-Path $reportsDir "report.json")) -AllowedExitCodes @($ExpectedCheckExitCode)

    Assert-ScenarioArtifacts -Scenario $Scenario -ReportsDir $reportsDir -StatePath $statePath
    Assert-ScenarioReport -Scenario $Scenario -ReportsDir $reportsDir -ExpectedStatus $ExpectedStatus -ExpectedObjectClass $ExpectedObjectClass -ExpectedSelectedPath $ExpectedSelectedPath -ExpectedRootCause $ExpectedRootCause -ExpectedFirstSuspectSegment $ExpectedFirstSuspectSegment
}

function Assert-ScenarioArtifacts {
    param(
        [string]$Scenario,
        [string]$ReportsDir,
        [string]$StatePath
    )

    Assert-True -Condition (Test-Path (Join-Path $ReportsDir "report.json")) -Message "report.json was not generated for $Scenario"
    Assert-True -Condition (Test-Path (Join-Path $ReportsDir "report.html")) -Message "report.html was not generated for $Scenario"
    Assert-True -Condition (Test-Path (Join-Path $ReportsDir "manifest.json")) -Message "manifest.json was not generated for $Scenario"
    Assert-True -Condition (Test-Path (Join-Path $ReportsDir "suspect_segments.csv")) -Message "suspect_segments.csv was not generated for $Scenario"
    Assert-True -Condition (Test-Path (Join-Path $ReportsDir "row_diff_sample.csv")) -Message "row_diff_sample.csv was not generated for $Scenario"
    Assert-True -Condition (Test-Path $StatePath) -Message "state.db was not generated for $Scenario"
}

function Assert-ScenarioReport {
    param(
        [string]$Scenario,
        [string]$ReportsDir,
        [string]$ExpectedStatus,
        [string]$ExpectedObjectClass,
        [string]$ExpectedSelectedPath,
        [string]$ExpectedRootCause,
        [string]$ExpectedFirstSuspectSegment = ""
    )

    $report = Get-Content (Join-Path $ReportsDir "report.json") -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-Equal -Actual $report.result.status -Expected $ExpectedStatus -Message "Unexpected report status for $Scenario"
    Assert-Equal -Actual $report.plan.object_class -Expected $ExpectedObjectClass -Message "Unexpected object class for $Scenario"
    Assert-Equal -Actual $report.plan.selected_path -Expected $ExpectedSelectedPath -Message "Unexpected selected path for $Scenario"
    Assert-Equal -Actual $report.result.root_cause -Expected $ExpectedRootCause -Message "Unexpected root cause for $Scenario"
    if ($ExpectedFirstSuspectSegment) {
        Assert-Equal -Actual $report.result.suspect_segments[0].segment_key -Expected $ExpectedFirstSuspectSegment -Message "Unexpected suspect segment for $Scenario"
    }
}

Ensure-BuildArtifacts
$itClasspath = Get-ItClasspath

Invoke-JdbcScenario -Scenario "postgres_simulated_jdbc" -Dialect "postgres" -PlannerMode "auto" -EstimatedRows 2 -MaxExactRows 100 -SegmentColumn "" -ExpectedCheckExitCode 0 -ExpectedStatus "CONSISTENT" -ExpectedObjectClass "small_table_once" -ExpectedSelectedPath "schema -> exact diff" -ExpectedRootCause "consistent"
Invoke-JdbcScenario -Scenario "hive_jdbc_partitioned" -Dialect "hive" -PlannerMode "segment_first" -EstimatedRows 1000000 -MaxExactRows 100 -SegmentColumn "dt" -ExpectedCheckExitCode 1 -ExpectedStatus "DIFF_FOUND" -ExpectedObjectClass "partitioned_big_table" -ExpectedSelectedPath "schema -> summary -> segment -> diff" -ExpectedRootCause "checksum_mismatch" -ExpectedFirstSuspectSegment "dt=2026-03-10"
Invoke-JdbcScenario -Scenario "doris_jdbc_result_diff" -Dialect "doris" -PlannerMode "auto" -EstimatedRows 2 -MaxExactRows 100 -SegmentColumn "" -ExpectedCheckExitCode 1 -ExpectedStatus "DIFF_FOUND" -ExpectedObjectClass "small_table_once" -ExpectedSelectedPath "schema -> exact diff" -ExpectedRootCause "checksum_mismatch"
Invoke-IcebergScenario -Scenario "iceberg_metadata_first" -ExpectedCheckExitCode 4 -ExpectedStatus "PARTIAL" -ExpectedObjectClass "lakehouse_object" -ExpectedSelectedPath "boundary metadata -> schema -> summary -> segment -> diff" -ExpectedRootCause "data_reader_unavailable" -ExpectedFirstSuspectSegment "manifest=0"

Write-Host ""
Write-Host "Second-layer local verification artifacts were written under $verifyRoot"
$global:LASTEXITCODE = 0
