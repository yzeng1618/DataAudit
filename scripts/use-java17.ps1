$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$commonRoot = $repoRoot
try {
    $gitDir = git -C $repoRoot rev-parse --git-common-dir 2>$null
    if ($LASTEXITCODE -eq 0 -and $gitDir) {
        $resolvedGitDir = if ([System.IO.Path]::IsPathRooted($gitDir)) {
            $gitDir
        } else {
            Join-Path $repoRoot $gitDir
        }
        $candidateRoot = Split-Path -Parent $resolvedGitDir
        if ($candidateRoot) {
            $commonRoot = $candidateRoot
        }
    }
} catch {
    $commonRoot = $repoRoot
}

$jdkHome = Join-Path $commonRoot ".tools\jdk-17"
$javaExe = Join-Path $jdkHome "bin\java.exe"

if (-not (Test-Path $javaExe)) {
    throw "Project-local JDK 17 was not found at $jdkHome. Download or install it first."
}

$env:JAVA_HOME = $jdkHome
$env:Path = "$jdkHome\bin;$env:Path"
if (-not $env:_JAVA_OPTIONS) {
    $env:_JAVA_OPTIONS = "-Xms16m -Xmx128m -XX:+UseSerialGC"
}
if (-not $env:MAVEN_OPTS) {
    $env:MAVEN_OPTS = "-Xms64m -Xmx512m -XX:MaxMetaspaceSize=256m -XX:+UseSerialGC"
}

Write-Host "JAVA_HOME=$env:JAVA_HOME"
& $javaExe -version
& mvn -v
