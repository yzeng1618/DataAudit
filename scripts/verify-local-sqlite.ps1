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

function Assert-StartsWith {
    param(
        [string]$Actual,
        [string]$ExpectedPrefix,
        [Parameter(Mandatory = $true)]
        [string]$Message
    )

    if (-not $Actual.StartsWith($ExpectedPrefix)) {
        throw "$Message. ExpectedPrefix='$ExpectedPrefix', Actual='$Actual'"
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
        [string]$TaskName,
        [string]$SourceDb,
        [string]$TargetDb,
        [string]$ReportDir,
        [Nullable[long]]$EstimatedRows,
        [string]$SegmentColumn,
        [bool]$IncludeKey = $true,
        [string]$BoundaryType = "job_finish",
        [string]$BoundaryReference = "latest",
        [string]$DdlMode = "compatible",
        [string]$RenameFrom = "",
        [string]$RenameTo = "",
        [string]$DeleteMode = "hard_delete",
        [bool]$ApplyAmountScale = $false,
        [bool]$CaseInsensitiveStatus = $false,
        [bool]$IncludeExtraNoteColumn = $false
    )

    $amountColumn = if ($RenameFrom -and $RenameTo) { $RenameFrom } else { "amount" }
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("task:")
    $lines.Add("  name: $TaskName")
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
    $lines.Add("object:")
    if ($IncludeKey) {
        $lines.Add("  key:")
        $lines.Add("    - order_id")
    }
    $lines.Add("  columns:")
    $lines.Add("    - order_id")
    $lines.Add("    - status")
    $lines.Add("    - $amountColumn")
    $lines.Add("    - dt")
    if ($IncludeExtraNoteColumn) {
        $lines.Add("    - extra_note")
    }
    if ($null -ne $EstimatedRows) {
        $lines.Add("  estimated_rows: $EstimatedRows")
    }
    if ($SegmentColumn) {
        $lines.Add("  partition_by:")
        $lines.Add("    - $SegmentColumn")
    }
    $lines.Add("")
    $lines.Add("normalize:")
    if ($ApplyAmountScale) {
        $lines.Add("  decimal_scale:")
        $lines.Add("    amount: 2")
    }
    if ($CaseInsensitiveStatus) {
        $lines.Add("  case_insensitive_columns:")
        $lines.Add("    - status")
    }
    if (-not $ApplyAmountScale -and -not $CaseInsensitiveStatus) {
        $lines.Add("  timezone: UTC")
    }
    $lines.Add("")
    $lines.Add("semantics:")
    $lines.Add("  dml:")
    $lines.Add("    delete:")
    $lines.Add("      mode: $DeleteMode")
    $lines.Add("  ddl:")
    $lines.Add("    mode: $DdlMode")
    if ($RenameFrom -and $RenameTo) {
        $lines.Add("    rename_mapping:")
        $lines.Add("      $RenameFrom`: $RenameTo")
    }
    $lines.Add("")
    $lines.Add("output:")
    $lines.Add("  dir: $ReportDir")

    Set-Content -Path $Path -Value $lines -Encoding UTF8
}

function Invoke-Scenario {
    param(
        [string]$Scenario,
        [Nullable[long]]$EstimatedRows,
        [string]$SegmentColumn,
        [int]$ExpectedPlanExitCode,
        [int]$ExpectedCheckExitCode,
        [string]$ExpectedStatus,
        [string]$ExpectedObjectClass,
        [string]$ExpectedSelectedPath,
        [string]$ExpectedRootCause,
        [string]$ExpectedConsistencyLevel = "",
        [string]$ExpectedLocalizationStrategy = "",
        [string]$ExpectedFirstSuspectSlice = "",
        [string]$ExpectedFirstSuspectSlicePrefix = "",
        [string]$ExpectedInconclusiveReason = "",
        [bool]$IncludeKey = $true,
        [string]$BoundaryType = "job_finish",
        [string]$BoundaryReference = "latest",
        [string]$DdlMode = "compatible",
        [string]$RenameFrom = "",
        [string]$RenameTo = "",
        [string]$DeleteMode = "hard_delete",
        [bool]$ApplyAmountScale = $false,
        [bool]$CaseInsensitiveStatus = $false,
        [bool]$IncludeExtraNoteColumn = $false
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

    Invoke-Native -FilePath "$env:JAVA_HOME\bin\java.exe" -Arguments @("-cp", "$sqliteJar;$slf4jApiJar;$classDir", "SampleSqliteSeeder", $Scenario, $sourceDb, $targetDb)

    New-TaskYaml -Path $taskFile `
        -TaskName $Scenario `
        -SourceDb $sourceDb `
        -TargetDb $targetDb `
        -ReportDir $reportsDir `
        -EstimatedRows $EstimatedRows `
        -SegmentColumn $SegmentColumn `
        -IncludeKey:$IncludeKey `
        -BoundaryType $BoundaryType `
        -BoundaryReference $BoundaryReference `
        -DdlMode $DdlMode `
        -RenameFrom $RenameFrom `
        -RenameTo $RenameTo `
        -DeleteMode $DeleteMode `
        -ApplyAmountScale:$ApplyAmountScale `
        -CaseInsensitiveStatus:$CaseInsensitiveStatus `
        -IncludeExtraNoteColumn:$IncludeExtraNoteColumn

    Write-Host ""
    Write-Host "=== Scenario: $Scenario ==="
    Invoke-Native -FilePath "$env:JAVA_HOME\bin\java.exe" -Arguments @("-jar", $jarPath, "plan", "-f", $taskFile) -AllowedExitCodes @($ExpectedPlanExitCode)
    Invoke-Native -FilePath "$env:JAVA_HOME\bin\java.exe" -Arguments @("-jar", $jarPath, "check", "-f", $taskFile) -AllowedExitCodes @($ExpectedCheckExitCode)
    Invoke-Native -FilePath "$env:JAVA_HOME\bin\java.exe" -Arguments @("-jar", $jarPath, "report", "show", (Join-Path $reportsDir "report.json")) -AllowedExitCodes @($ExpectedCheckExitCode)

    $reportPath = Join-Path $reportsDir "report.json"
    Assert-True -Condition (Test-Path $reportPath) -Message "report.json was not generated for $Scenario"
    Assert-True -Condition (Test-Path (Join-Path $reportsDir "report.html")) -Message "report.html was not generated for $Scenario"
    Assert-True -Condition (Test-Path (Join-Path $reportsDir "manifest.json")) -Message "manifest.json was not generated for $Scenario"
    Assert-True -Condition (Test-Path (Join-Path $reportsDir "suspect_slices.csv")) -Message "suspect_slices.csv was not generated for $Scenario"
    Assert-True -Condition (Test-Path (Join-Path $reportsDir "row_diff_sample.csv")) -Message "row_diff_sample.csv was not generated for $Scenario"
    Assert-True -Condition (Test-Path $statePath) -Message "state.db was not generated for $Scenario"

    $report = Get-Content $reportPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-Equal -Actual $report.result.status -Expected $ExpectedStatus -Message "Unexpected report status for $Scenario"
    Assert-Equal -Actual $report.plan.object_class -Expected $ExpectedObjectClass -Message "Unexpected object class for $Scenario"
    Assert-Equal -Actual $report.plan.selected_path -Expected $ExpectedSelectedPath -Message "Unexpected selected path for $Scenario"
    Assert-Equal -Actual $report.result.root_cause -Expected $ExpectedRootCause -Message "Unexpected root cause for $Scenario"
    if ($ExpectedConsistencyLevel) {
        Assert-Equal -Actual $report.result.consistency_level -Expected $ExpectedConsistencyLevel -Message "Unexpected consistency level for $Scenario"
    }
    if ($ExpectedLocalizationStrategy) {
        Assert-Equal -Actual $report.plan.localization_strategy -Expected $ExpectedLocalizationStrategy -Message "Unexpected localization strategy for $Scenario"
    }
    if ($ExpectedFirstSuspectSlice) {
        Assert-Equal -Actual $report.result.suspect_slices[0].slice_key -Expected $ExpectedFirstSuspectSlice -Message "Unexpected suspect slice for $Scenario"
    }
    if ($ExpectedFirstSuspectSlicePrefix) {
        Assert-StartsWith -Actual ([string]$report.result.suspect_slices[0].slice_key) -ExpectedPrefix $ExpectedFirstSuspectSlicePrefix -Message "Unexpected suspect slice prefix for $Scenario"
    }
    if ($ExpectedInconclusiveReason) {
        Assert-Equal -Actual $report.result.inconclusive_reason -Expected $ExpectedInconclusiveReason -Message "Unexpected inconclusive reason for $Scenario"
    }
}

New-Item -ItemType Directory -Force -Path $verifyRoot | Out-Null
$sqliteJar = Get-SqliteJdbcJar
$slf4jApiJar = Get-Slf4jApiJar

Invoke-Native -FilePath "mvn" -Arguments @("-q", "-pl", "data-audit-cli", "-am", "-DskipTests", "package")

New-Item -ItemType Directory -Force -Path $classDir | Out-Null
Invoke-Native -FilePath "$env:JAVA_HOME\bin\javac.exe" -Arguments @("-cp", "$sqliteJar;$slf4jApiJar", "-d", $classDir, (Join-Path $PSScriptRoot "java\SampleSqliteSeeder.java"))

Invoke-Scenario -Scenario "consistent_small" -EstimatedRows 2 -SegmentColumn "" -ExpectedPlanExitCode 0 -ExpectedCheckExitCode 0 -ExpectedStatus "CONSISTENT" -ExpectedObjectClass "small_table_once" -ExpectedSelectedPath "schema -> exact diff" -ExpectedRootCause "consistent" -ExpectedConsistencyLevel "exact"
Invoke-Scenario -Scenario "small_diff" -EstimatedRows 2 -SegmentColumn "" -ExpectedPlanExitCode 0 -ExpectedCheckExitCode 1 -ExpectedStatus "DIFF_FOUND" -ExpectedObjectClass "small_table_once" -ExpectedSelectedPath "schema -> exact diff" -ExpectedRootCause "latest_state_mismatch" -ExpectedConsistencyLevel "exact" -ApplyAmountScale:$true
Invoke-Scenario -Scenario "keyless_multiset" -EstimatedRows 3 -SegmentColumn "" -ExpectedPlanExitCode 0 -ExpectedCheckExitCode 1 -ExpectedStatus "DIFF_FOUND" -ExpectedObjectClass "small_table_once" -ExpectedSelectedPath "schema -> exact diff" -ExpectedRootCause "keyless_multiset_mismatch" -ExpectedConsistencyLevel "exact" -IncludeKey:$false
Invoke-Scenario -Scenario "partition_mismatch" -EstimatedRows 1000000 -SegmentColumn "dt" -ExpectedPlanExitCode 0 -ExpectedCheckExitCode 1 -ExpectedStatus "DIFF_FOUND" -ExpectedObjectClass "partitioned_big_table" -ExpectedSelectedPath "gate -> signal -> localization -> drilldown" -ExpectedRootCause "latest_state_mismatch" -ExpectedConsistencyLevel "exact" -ExpectedLocalizationStrategy "natural_slice" -ExpectedFirstSuspectSlice "dt=2026-03-10" -ApplyAmountScale:$true
Invoke-Scenario -Scenario "bucket_mismatch" -EstimatedRows 1000000 -SegmentColumn "" -ExpectedPlanExitCode 0 -ExpectedCheckExitCode 1 -ExpectedStatus "DIFF_FOUND" -ExpectedObjectClass "partitioned_big_table" -ExpectedSelectedPath "gate -> signal -> localization -> drilldown" -ExpectedRootCause "latest_state_mismatch" -ExpectedConsistencyLevel "exact" -ExpectedLocalizationStrategy "virtual_bucket" -ExpectedFirstSuspectSlicePrefix "bucket="
Invoke-Scenario -Scenario "keyless_large_inconclusive" -EstimatedRows 1000000 -SegmentColumn "" -ExpectedPlanExitCode 0 -ExpectedCheckExitCode 3 -ExpectedStatus "INCONCLUSIVE" -ExpectedObjectClass "partitioned_big_table" -ExpectedSelectedPath "gate -> signal -> localization -> drilldown" -ExpectedRootCause "sampling_inconclusive" -ExpectedConsistencyLevel "high_confidence" -ExpectedLocalizationStrategy "sample_first" -ExpectedInconclusiveReason "keyless_large_object_requires_exact_or_natural_slice" -IncludeKey:$false
Invoke-Scenario -Scenario "schema_mismatch" -EstimatedRows 2 -SegmentColumn "" -ExpectedPlanExitCode 0 -ExpectedCheckExitCode 1 -ExpectedStatus "DIFF_FOUND" -ExpectedObjectClass "small_table_once" -ExpectedSelectedPath "schema -> exact diff" -ExpectedRootCause "schema_mismatch" -ExpectedConsistencyLevel "exact" -DdlMode "strict" -IncludeExtraNoteColumn:$true
Invoke-Scenario -Scenario "unstable_snapshot_jdbc" -EstimatedRows 2 -SegmentColumn "" -ExpectedPlanExitCode 5 -ExpectedCheckExitCode 5 -ExpectedStatus "REFUSED" -ExpectedObjectClass "" -ExpectedSelectedPath "" -ExpectedRootCause "unstable_boundary" -BoundaryType "snapshot" -BoundaryReference "latest"
Invoke-Scenario -Scenario "ddl_rename_compatible" -EstimatedRows 1 -SegmentColumn "" -ExpectedPlanExitCode 0 -ExpectedCheckExitCode 0 -ExpectedStatus "CONSISTENT" -ExpectedObjectClass "small_table_once" -ExpectedSelectedPath "schema -> exact diff" -ExpectedRootCause "consistent" -ExpectedConsistencyLevel "exact" -RenameFrom "old_amount" -RenameTo "amount" -ApplyAmountScale:$true -CaseInsensitiveStatus:$true
Invoke-Scenario -Scenario "delete_hard_delete_mismatch" -EstimatedRows 2 -SegmentColumn "" -ExpectedPlanExitCode 0 -ExpectedCheckExitCode 1 -ExpectedStatus "DIFF_FOUND" -ExpectedObjectClass "small_table_once" -ExpectedSelectedPath "schema -> exact diff" -ExpectedRootCause "delete_not_effective" -ExpectedConsistencyLevel "exact" -DeleteMode "hard_delete"

Write-Host ""
Write-Host "Verification artifacts were written under $verifyRoot"
$global:LASTEXITCODE = 0
