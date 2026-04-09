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
        [string]$RenameFrom = "",
        [string]$RenameTo = "",
        [bool]$ApplyAmountScale = $false,
        [bool]$CaseInsensitiveStatus = $false
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
    if ($null -ne $EstimatedRows) {
        $lines.Add("  estimated_rows: $EstimatedRows")
    }
    if ($SegmentColumn) {
        $lines.Add("  partition_by:")
        $lines.Add("    - $SegmentColumn")
    }
    $lines.Add("")
    $lines.Add("normalize:")
    $lines.Add("  timezone: UTC")
    if ($ApplyAmountScale) {
        $lines.Add("  decimal_scale:")
        $lines.Add("    amount: 2")
    }
    if ($CaseInsensitiveStatus) {
        $lines.Add("  case_insensitive_columns:")
        $lines.Add("    - status")
    }
    if ($RenameFrom -and $RenameTo) {
        $lines.Add("")
        $lines.Add("semantics:")
        $lines.Add("  ddl:")
        $lines.Add("    rename_mapping:")
        $lines.Add("      $RenameFrom`: $RenameTo")
    }
    $lines.Add("")
    $lines.Add("output:")
    $lines.Add("  dir: $ReportDir")

    Set-Content -Path $Path -Value $lines -Encoding UTF8
}

function Assert-LegacyFieldsRemoved {
    param($Report, [string]$Scenario)

    Assert-True -Condition ($null -eq $Report.plan.object_class) -Message "Legacy field plan.object_class should be absent for $Scenario"
    Assert-True -Condition ($null -eq $Report.plan.selected_path) -Message "Legacy field plan.selected_path should be absent for $Scenario"
    Assert-True -Condition ($null -eq $Report.plan.signal_backend) -Message "Legacy field plan.signal_backend should be absent for $Scenario"
    Assert-True -Condition ($null -eq $Report.result.schema_issues) -Message "Legacy field result.schema_issues should be absent for $Scenario"
    Assert-True -Condition ($null -eq $Report.result.dml_audit) -Message "Legacy field result.dml_audit should be absent for $Scenario"
    Assert-True -Condition ($null -eq $Report.result.ddl_audit) -Message "Legacy field result.ddl_audit should be absent for $Scenario"
}

function Invoke-Scenario {
    param(
        [string]$Scenario,
        [Nullable[long]]$EstimatedRows,
        [string]$SegmentColumn,
        [int]$ExpectedPlanExitCode,
        [int]$ExpectedCheckExitCode,
        [string]$ExpectedStatus,
        [string]$ExpectedScaleClass = "",
        [string]$ExpectedSignalStrategy = "",
        [string]$ExpectedLocalizationStrategy = "",
        [string]$ExpectedProofMode = "",
        [string]$ExpectedConfidence = "",
        [string]$ExpectedRootCause = "",
        [string]$ExpectedFirstSuspectSlice = "",
        [string]$ExpectedFirstSuspectSlicePrefix = "",
        [object]$ExpectedNoKeyMode = $null,
        [string]$ExpectedFallbackReason = "",
        [bool]$IncludeKey = $true,
        [string]$BoundaryType = "job_finish",
        [string]$BoundaryReference = "latest",
        [string]$RenameFrom = "",
        [string]$RenameTo = "",
        [bool]$ApplyAmountScale = $false,
        [bool]$CaseInsensitiveStatus = $false
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
        -RenameFrom $RenameFrom `
        -RenameTo $RenameTo `
        -ApplyAmountScale:$ApplyAmountScale `
        -CaseInsensitiveStatus:$CaseInsensitiveStatus

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
    Assert-LegacyFieldsRemoved -Report $report -Scenario $Scenario
    Assert-Equal -Actual $report.result.status -Expected $ExpectedStatus -Message "Unexpected report status for $Scenario"

    if ($ExpectedScaleClass) {
        Assert-Equal -Actual $report.plan.scale_class -Expected $ExpectedScaleClass -Message "Unexpected scale class for $Scenario"
    }
    if ($ExpectedSignalStrategy) {
        Assert-Equal -Actual $report.plan.signal_strategy -Expected $ExpectedSignalStrategy -Message "Unexpected signal strategy for $Scenario"
    }
    if ($ExpectedLocalizationStrategy) {
        Assert-Equal -Actual $report.plan.localization_strategy -Expected $ExpectedLocalizationStrategy -Message "Unexpected localization strategy for $Scenario"
    }
    if ($ExpectedProofMode) {
        Assert-Equal -Actual $report.result.proof_mode -Expected $ExpectedProofMode -Message "Unexpected proof mode for $Scenario"
    }
    if ($ExpectedConfidence) {
        Assert-Equal -Actual $report.result.confidence -Expected $ExpectedConfidence -Message "Unexpected confidence for $Scenario"
    }

    $actualRootCause = [string]$report.result.root_cause
    if ([string]::IsNullOrEmpty($ExpectedRootCause)) {
        Assert-True -Condition ([string]::IsNullOrEmpty($actualRootCause)) -Message "Expected empty root cause for $Scenario"
    } else {
        Assert-Equal -Actual $actualRootCause -Expected $ExpectedRootCause -Message "Unexpected root cause for $Scenario"
    }

    if ($ExpectedFirstSuspectSlice) {
        Assert-Equal -Actual $report.result.suspect_slices[0].slice_key -Expected $ExpectedFirstSuspectSlice -Message "Unexpected suspect slice for $Scenario"
    }
    if ($ExpectedFirstSuspectSlicePrefix) {
        Assert-StartsWith -Actual ([string]$report.result.suspect_slices[0].slice_key) -ExpectedPrefix $ExpectedFirstSuspectSlicePrefix -Message "Unexpected suspect slice prefix for $Scenario"
    }
    if ($null -ne $ExpectedNoKeyMode) {
        Assert-Equal -Actual $report.result.no_key_mode -Expected $ExpectedNoKeyMode -Message "Unexpected no_key_mode for $Scenario"
    }
    if ([string]::IsNullOrEmpty($ExpectedFallbackReason)) {
        Assert-True -Condition ([string]::IsNullOrEmpty([string]$report.result.fallback_reason)) -Message "Expected empty fallback reason for $Scenario"
    } else {
        Assert-Equal -Actual $report.result.fallback_reason -Expected $ExpectedFallbackReason -Message "Unexpected fallback reason for $Scenario"
    }
}

New-Item -ItemType Directory -Force -Path $verifyRoot | Out-Null
$sqliteJar = Get-SqliteJdbcJar
$slf4jApiJar = Get-Slf4jApiJar

Invoke-Native -FilePath "mvn" -Arguments @("-q", "-pl", "data-audit-cli", "-am", "-DskipTests", "package")

New-Item -ItemType Directory -Force -Path $classDir | Out-Null
Invoke-Native -FilePath "$env:JAVA_HOME\bin\javac.exe" -Arguments @("-cp", "$sqliteJar;$slf4jApiJar", "-d", $classDir, (Join-Path $PSScriptRoot "java\SampleSqliteSeeder.java"))

Invoke-Scenario -Scenario "consistent_small" -EstimatedRows 2 -SegmentColumn "" -ExpectedPlanExitCode 0 -ExpectedCheckExitCode 0 -ExpectedStatus "CONSISTENT" -ExpectedScaleClass "SMALL" -ExpectedSignalStrategy "global_row_count_plus_checksum" -ExpectedLocalizationStrategy "none" -ExpectedProofMode "GLOBAL_CHECKSUM" -ExpectedConfidence "HIGH" -ExpectedNoKeyMode $false
Invoke-Scenario -Scenario "small_diff" -EstimatedRows 2 -SegmentColumn "" -ExpectedPlanExitCode 0 -ExpectedCheckExitCode 1 -ExpectedStatus "DIFF_FOUND" -ExpectedScaleClass "SMALL" -ExpectedSignalStrategy "global_row_count_plus_checksum" -ExpectedLocalizationStrategy "none" -ExpectedProofMode "EXACT_DIFF" -ExpectedConfidence "EXACT" -ExpectedRootCause "value_mismatch" -ExpectedNoKeyMode $false -ApplyAmountScale:$true
Invoke-Scenario -Scenario "keyless_multiset" -EstimatedRows 3 -SegmentColumn "" -ExpectedPlanExitCode 0 -ExpectedCheckExitCode 1 -ExpectedStatus "DIFF_FOUND" -ExpectedScaleClass "SMALL" -ExpectedSignalStrategy "global_row_count_plus_checksum" -ExpectedLocalizationStrategy "none" -ExpectedProofMode "EXACT_DIFF" -ExpectedConfidence "EXACT" -ExpectedRootCause "duplicate_or_missing" -ExpectedNoKeyMode $false -IncludeKey:$false
Invoke-Scenario -Scenario "partition_mismatch" -EstimatedRows 1000000 -SegmentColumn "dt" -ExpectedPlanExitCode 0 -ExpectedCheckExitCode 1 -ExpectedStatus "DIFF_FOUND" -ExpectedScaleClass "LARGE" -ExpectedSignalStrategy "global_row_count_plus_grouped_checksum" -ExpectedLocalizationStrategy "partition_window" -ExpectedProofMode "EXACT_DIFF" -ExpectedConfidence "EXACT" -ExpectedRootCause "value_mismatch" -ExpectedFirstSuspectSlice "dt=2026-03-10" -ExpectedNoKeyMode $false -ApplyAmountScale:$true
Invoke-Scenario -Scenario "bucket_mismatch" -EstimatedRows 1000000 -SegmentColumn "" -ExpectedPlanExitCode 0 -ExpectedCheckExitCode 1 -ExpectedStatus "DIFF_FOUND" -ExpectedScaleClass "LARGE" -ExpectedSignalStrategy "global_row_count_plus_grouped_checksum" -ExpectedLocalizationStrategy "key_hash_bucket" -ExpectedProofMode "EXACT_DIFF" -ExpectedConfidence "EXACT" -ExpectedRootCause "value_mismatch" -ExpectedFirstSuspectSlicePrefix "bucket=" -ExpectedNoKeyMode $false
Invoke-Scenario -Scenario "keyless_large_consistent" -EstimatedRows 1000000 -SegmentColumn "" -ExpectedPlanExitCode 0 -ExpectedCheckExitCode 0 -ExpectedStatus "CONSISTENT" -ExpectedScaleClass "LARGE" -ExpectedSignalStrategy "global_row_count_plus_grouped_checksum" -ExpectedLocalizationStrategy "no_key_xor" -ExpectedProofMode "XOR_CHECKSUM_PLUS_SAMPLE" -ExpectedConfidence "MEDIUM" -ExpectedNoKeyMode $true -ExpectedFallbackReason "no_key_xor_fallback" -IncludeKey:$false
Invoke-Scenario -Scenario "keyless_large_inconclusive" -EstimatedRows 1000000 -SegmentColumn "" -ExpectedPlanExitCode 0 -ExpectedCheckExitCode 1 -ExpectedStatus "DIFF_FOUND" -ExpectedScaleClass "LARGE" -ExpectedSignalStrategy "global_row_count_plus_grouped_checksum" -ExpectedLocalizationStrategy "no_key_xor" -ExpectedProofMode "XOR_CHECKSUM_PLUS_SAMPLE" -ExpectedConfidence "MEDIUM" -ExpectedRootCause "value_mismatch" -ExpectedFirstSuspectSlice "full_table" -ExpectedNoKeyMode $true -ExpectedFallbackReason "no_key_xor_fallback" -IncludeKey:$false
Invoke-Scenario -Scenario "unstable_snapshot_jdbc" -EstimatedRows 2 -SegmentColumn "" -ExpectedPlanExitCode 5 -ExpectedCheckExitCode 5 -ExpectedStatus "UNSTABLE_BOUNDARY" -BoundaryType "snapshot" -BoundaryReference "latest"
Invoke-Scenario -Scenario "ddl_rename_compatible" -EstimatedRows 1 -SegmentColumn "" -ExpectedPlanExitCode 0 -ExpectedCheckExitCode 0 -ExpectedStatus "CONSISTENT" -ExpectedScaleClass "SMALL" -ExpectedSignalStrategy "global_row_count_plus_checksum" -ExpectedLocalizationStrategy "none" -ExpectedProofMode "GLOBAL_CHECKSUM" -ExpectedConfidence "HIGH" -ExpectedNoKeyMode $false -RenameFrom "old_amount" -RenameTo "amount" -ApplyAmountScale:$true -CaseInsensitiveStatus:$true
Invoke-Scenario -Scenario "delete_hard_delete_mismatch" -EstimatedRows 2 -SegmentColumn "" -ExpectedPlanExitCode 0 -ExpectedCheckExitCode 1 -ExpectedStatus "DIFF_FOUND" -ExpectedScaleClass "SMALL" -ExpectedSignalStrategy "global_row_count_plus_checksum" -ExpectedLocalizationStrategy "none" -ExpectedProofMode "EXACT_DIFF" -ExpectedConfidence "EXACT" -ExpectedRootCause "row_count_mismatch" -ExpectedNoKeyMode $false

Write-Host ""
Write-Host "Verification artifacts were written under $verifyRoot"
$global:LASTEXITCODE = 0
