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

    Invoke-Native -FilePath "mvn" -Arguments @("-q", "-o", "-pl", "data-audit-it", "-am", "-DskipTests", "install")
    Invoke-Native -FilePath "mvn" -Arguments @(
        "-q",
        "-o",
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
        [string]$TaskName,
        [string]$SourceDb,
        [string]$TargetDb,
        [string]$ReportDir,
        [string]$Dialect,
        [Nullable[long]]$EstimatedRows,
        [string]$SegmentColumn
    )

    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("task:")
    $lines.Add("  name: $TaskName")
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
    $lines.Add("  columns:")
    $lines.Add("    - order_id")
    $lines.Add("    - status")
    $lines.Add("    - amount")
    $lines.Add("    - dt")
    if ($null -ne $EstimatedRows) {
        $lines.Add("  estimated_rows: $EstimatedRows")
    }
    if ($SegmentColumn) {
        $lines.Add("  partition_by:")
        $lines.Add("    - $SegmentColumn")
    }
    $lines.Add("")
    $lines.Add("normalize:")
    $lines.Add("  decimal_scale:")
    $lines.Add("    amount: 2")
    $lines.Add("")
    $lines.Add("output:")
    $lines.Add("  dir: $ReportDir")

    Set-Content -Path $Path -Value $lines -Encoding UTF8
}

function New-JdbcToIcebergTaskYaml {
    param(
        [string]$Path,
        [string]$TaskName,
        [string]$SourceDb,
        [string]$TableLocation,
        [string]$ReportDir
    )

    $lines = @(
        "task:",
        "  name: $TaskName",
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
        "object:",
        "  key:",
        "    - order_id",
        "  columns:",
        "    - order_id",
        "    - status",
        "    - amount",
        "    - dt",
        "  estimated_rows: 1000000",
        "  partition_by:",
        "    - dt",
        "",
        "normalize:",
        "  decimal_scale:",
        "    amount: 2",
        "",
        "semantics:",
        "  ddl:",
        "    mode: compatible",
        "    type_rules:",
        "      - from: integer",
        "        to: long",
        "        action: allow",
        "",
        "output:",
        "  dir: $ReportDir"
    )

    Set-Content -Path $Path -Value $lines -Encoding UTF8
}

function New-IcebergToJdbcTaskYaml {
    param(
        [string]$Path,
        [string]$TaskName,
        [string]$TableLocation,
        [string]$TargetDb,
        [string]$ReportDir
    )

    $lines = @(
        "task:",
        "  name: $TaskName",
        "  mode: post_check",
        "",
        "boundary:",
        "  type: snapshot",
        "  reference: latest",
        "",
        "source:",
        "  type: iceberg",
        "  location: $TableLocation",
        "  table: orders",
        "",
        "target:",
        "  type: jdbc",
        "  url: jdbc:sqlite:$TargetDb",
        "  table: orders",
        "  options:",
        "    dialect: postgres",
        "",
        "object:",
        "  key:",
        "    - order_id",
        "  columns:",
        "    - order_id",
        "    - status",
        "    - amount",
        "    - dt",
        "  estimated_rows: 1000000",
        "  partition_by:",
        "    - dt",
        "",
        "normalize:",
        "  decimal_scale:",
        "    amount: 2",
        "",
        "semantics:",
        "  ddl:",
        "    mode: compatible",
        "    type_rules:",
        "      - from: long",
        "        to: integer",
        "        action: allow",
        "",
        "output:",
        "  dir: $ReportDir"
    )

    Set-Content -Path $Path -Value $lines -Encoding UTF8
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
    Assert-True -Condition (Test-Path (Join-Path $ReportsDir "suspect_slices.csv")) -Message "suspect_slices.csv was not generated for $Scenario"
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
        [string]$ExpectedFirstSuspectSlice = ""
    )

    $report = Get-Content (Join-Path $ReportsDir "report.json") -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-Equal -Actual $report.result.status -Expected $ExpectedStatus -Message "Unexpected report status for $Scenario"
    Assert-Equal -Actual $report.plan.object_class -Expected $ExpectedObjectClass -Message "Unexpected object class for $Scenario"
    Assert-Equal -Actual $report.plan.selected_path -Expected $ExpectedSelectedPath -Message "Unexpected selected path for $Scenario"
    Assert-Equal -Actual $report.result.root_cause -Expected $ExpectedRootCause -Message "Unexpected root cause for $Scenario"
    if ($ExpectedFirstSuspectSlice) {
        Assert-Equal -Actual $report.result.suspect_slices[0].slice_key -Expected $ExpectedFirstSuspectSlice -Message "Unexpected suspect slice for $Scenario"
    }
}

function Invoke-JdbcScenario {
    param(
        [string]$Scenario,
        [string]$Dialect,
        [Nullable[long]]$EstimatedRows,
        [string]$SegmentColumn,
        [int]$ExpectedCheckExitCode,
        [string]$ExpectedStatus,
        [string]$ExpectedObjectClass,
        [string]$ExpectedSelectedPath,
        [string]$ExpectedRootCause,
        [string]$ExpectedFirstSuspectSlice = ""
    )

    $scenarioRoot = Join-Path $verifyRoot $Scenario
    $sourceDb = Join-Path $scenarioRoot "source.db"
    $targetDb = Join-Path $scenarioRoot "target.db"
    $reportsDir = Join-Path $scenarioRoot "reports"
    $statePath = Join-Path $reportsDir "state.db"
    $taskFile = Join-Path $scenarioRoot "task.yaml"

    if (Test-Path $scenarioRoot) {
        Remove-Item $scenarioRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $scenarioRoot | Out-Null

    Invoke-Native -FilePath "$env:JAVA_HOME\bin\java.exe" -Arguments @("-cp", $itClasspath, "io.github.dataaudit.it.support.SecondLayerFixtureBuilder", $Scenario, $sourceDb, $targetDb)
    New-JdbcTaskYaml -Path $taskFile -TaskName $Scenario -SourceDb $sourceDb -TargetDb $targetDb -ReportDir $reportsDir -Dialect $Dialect -EstimatedRows $EstimatedRows -SegmentColumn $SegmentColumn

    Write-Host ""
    Write-Host "=== Scenario: $Scenario ==="
    Invoke-Native -FilePath "$env:JAVA_HOME\bin\java.exe" -Arguments @("-jar", $jarPath, "plan", "-f", $taskFile)
    Invoke-Native -FilePath "$env:JAVA_HOME\bin\java.exe" -Arguments @("-jar", $jarPath, "check", "-f", $taskFile) -AllowedExitCodes @($ExpectedCheckExitCode)
    Invoke-Native -FilePath "$env:JAVA_HOME\bin\java.exe" -Arguments @("-jar", $jarPath, "report", "show", (Join-Path $reportsDir "report.json")) -AllowedExitCodes @($ExpectedCheckExitCode)

    Assert-ScenarioArtifacts -Scenario $Scenario -ReportsDir $reportsDir -StatePath $statePath
    Assert-ScenarioReport -Scenario $Scenario -ReportsDir $reportsDir -ExpectedStatus $ExpectedStatus -ExpectedObjectClass $ExpectedObjectClass -ExpectedSelectedPath $ExpectedSelectedPath -ExpectedRootCause $ExpectedRootCause -ExpectedFirstSuspectSlice $ExpectedFirstSuspectSlice
}

function Invoke-JdbcToIcebergScenario {
    param(
        [string]$Scenario,
        [int]$ExpectedCheckExitCode,
        [string]$ExpectedStatus,
        [string]$ExpectedObjectClass,
        [string]$ExpectedSelectedPath,
        [string]$ExpectedRootCause,
        [string]$ExpectedFirstSuspectSlice = ""
    )

    $scenarioRoot = Join-Path $verifyRoot $Scenario
    $sourceDb = Join-Path $scenarioRoot "source.db"
    $tableLocation = Join-Path $scenarioRoot "warehouse\orders"
    $reportsDir = Join-Path $scenarioRoot "reports"
    $statePath = Join-Path $reportsDir "state.db"
    $taskFile = Join-Path $scenarioRoot "task.yaml"

    if (Test-Path $scenarioRoot) {
        Remove-Item $scenarioRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $scenarioRoot | Out-Null

    Invoke-Native -FilePath "$env:JAVA_HOME\bin\java.exe" -Arguments @("-cp", $itClasspath, "io.github.dataaudit.it.support.SecondLayerFixtureBuilder", $Scenario, $sourceDb, $tableLocation)
    New-JdbcToIcebergTaskYaml -Path $taskFile -TaskName $Scenario -SourceDb $sourceDb -TableLocation $tableLocation -ReportDir $reportsDir

    Write-Host ""
    Write-Host "=== Scenario: $Scenario ==="
    Invoke-Native -FilePath "$env:JAVA_HOME\bin\java.exe" -Arguments @("-jar", $jarPath, "plan", "-f", $taskFile)
    Invoke-Native -FilePath "$env:JAVA_HOME\bin\java.exe" -Arguments @("-jar", $jarPath, "check", "-f", $taskFile) -AllowedExitCodes @($ExpectedCheckExitCode)
    Invoke-Native -FilePath "$env:JAVA_HOME\bin\java.exe" -Arguments @("-jar", $jarPath, "report", "show", (Join-Path $reportsDir "report.json")) -AllowedExitCodes @($ExpectedCheckExitCode)

    Assert-ScenarioArtifacts -Scenario $Scenario -ReportsDir $reportsDir -StatePath $statePath
    Assert-ScenarioReport -Scenario $Scenario -ReportsDir $reportsDir -ExpectedStatus $ExpectedStatus -ExpectedObjectClass $ExpectedObjectClass -ExpectedSelectedPath $ExpectedSelectedPath -ExpectedRootCause $ExpectedRootCause -ExpectedFirstSuspectSlice $ExpectedFirstSuspectSlice
}

function Invoke-IcebergToJdbcScenario {
    param(
        [string]$Scenario,
        [int]$ExpectedCheckExitCode,
        [string]$ExpectedStatus,
        [string]$ExpectedObjectClass,
        [string]$ExpectedSelectedPath,
        [string]$ExpectedRootCause,
        [string]$ExpectedFirstSuspectSlice = ""
    )

    $scenarioRoot = Join-Path $verifyRoot $Scenario
    $tableLocation = Join-Path $scenarioRoot "warehouse\orders"
    $targetDb = Join-Path $scenarioRoot "target.db"
    $reportsDir = Join-Path $scenarioRoot "reports"
    $statePath = Join-Path $reportsDir "state.db"
    $taskFile = Join-Path $scenarioRoot "task.yaml"

    if (Test-Path $scenarioRoot) {
        Remove-Item $scenarioRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $scenarioRoot | Out-Null

    Invoke-Native -FilePath "$env:JAVA_HOME\bin\java.exe" -Arguments @("-cp", $itClasspath, "io.github.dataaudit.it.support.SecondLayerFixtureBuilder", $Scenario, $tableLocation, $targetDb)
    New-IcebergToJdbcTaskYaml -Path $taskFile -TaskName $Scenario -TableLocation $tableLocation -TargetDb $targetDb -ReportDir $reportsDir

    Write-Host ""
    Write-Host "=== Scenario: $Scenario ==="
    Invoke-Native -FilePath "$env:JAVA_HOME\bin\java.exe" -Arguments @("-jar", $jarPath, "plan", "-f", $taskFile)
    Invoke-Native -FilePath "$env:JAVA_HOME\bin\java.exe" -Arguments @("-jar", $jarPath, "check", "-f", $taskFile) -AllowedExitCodes @($ExpectedCheckExitCode)
    Invoke-Native -FilePath "$env:JAVA_HOME\bin\java.exe" -Arguments @("-jar", $jarPath, "report", "show", (Join-Path $reportsDir "report.json")) -AllowedExitCodes @($ExpectedCheckExitCode)

    Assert-ScenarioArtifacts -Scenario $Scenario -ReportsDir $reportsDir -StatePath $statePath
    Assert-ScenarioReport -Scenario $Scenario -ReportsDir $reportsDir -ExpectedStatus $ExpectedStatus -ExpectedObjectClass $ExpectedObjectClass -ExpectedSelectedPath $ExpectedSelectedPath -ExpectedRootCause $ExpectedRootCause -ExpectedFirstSuspectSlice $ExpectedFirstSuspectSlice
}

Ensure-BuildArtifacts
$itClasspath = Get-ItClasspath

Invoke-JdbcScenario -Scenario "postgres_simulated_jdbc" -Dialect "postgres" -EstimatedRows 2 -SegmentColumn "" -ExpectedCheckExitCode 0 -ExpectedStatus "CONSISTENT" -ExpectedObjectClass "small_table_once" -ExpectedSelectedPath "schema -> exact diff" -ExpectedRootCause "consistent"
Invoke-JdbcScenario -Scenario "mysql_simulated_jdbc" -Dialect "mysql" -EstimatedRows 2 -SegmentColumn "" -ExpectedCheckExitCode 0 -ExpectedStatus "CONSISTENT" -ExpectedObjectClass "small_table_once" -ExpectedSelectedPath "schema -> exact diff" -ExpectedRootCause "consistent"
Invoke-JdbcScenario -Scenario "hive_jdbc_partitioned" -Dialect "hive" -EstimatedRows 1000000 -SegmentColumn "dt" -ExpectedCheckExitCode 1 -ExpectedStatus "DIFF_FOUND" -ExpectedObjectClass "partitioned_big_table" -ExpectedSelectedPath "gate -> signal -> localization -> drilldown" -ExpectedRootCause "latest_state_mismatch" -ExpectedFirstSuspectSlice "dt=2026-03-10"
Invoke-JdbcScenario -Scenario "doris_jdbc_result_diff" -Dialect "doris" -EstimatedRows 2 -SegmentColumn "" -ExpectedCheckExitCode 1 -ExpectedStatus "DIFF_FOUND" -ExpectedObjectClass "small_table_once" -ExpectedSelectedPath "schema -> exact diff" -ExpectedRootCause "latest_state_mismatch"
Invoke-JdbcToIcebergScenario -Scenario "jdbc_to_iceberg_consistent" -ExpectedCheckExitCode 0 -ExpectedStatus "CONSISTENT" -ExpectedObjectClass "lakehouse_object" -ExpectedSelectedPath "boundary metadata -> schema -> signal -> localization -> drilldown" -ExpectedRootCause "consistent"
Invoke-JdbcToIcebergScenario -Scenario "jdbc_to_iceberg_diff" -ExpectedCheckExitCode 1 -ExpectedStatus "DIFF_FOUND" -ExpectedObjectClass "lakehouse_object" -ExpectedSelectedPath "boundary metadata -> schema -> signal -> localization -> drilldown" -ExpectedRootCause "latest_state_mismatch" -ExpectedFirstSuspectSlice "dt=2026-03-10"
Invoke-IcebergToJdbcScenario -Scenario "iceberg_to_jdbc_partitioned" -ExpectedCheckExitCode 1 -ExpectedStatus "DIFF_FOUND" -ExpectedObjectClass "lakehouse_object" -ExpectedSelectedPath "boundary metadata -> schema -> signal -> localization -> drilldown" -ExpectedRootCause "latest_state_mismatch" -ExpectedFirstSuspectSlice "dt=2026-03-10"

Write-Host ""
Write-Host "Second-layer local verification artifacts were written under $verifyRoot"
$global:LASTEXITCODE = 0
