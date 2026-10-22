param([switch]$Test, [switch]$Probe, [switch]$Recover, [switch]$BuildOnly, [switch]$Telemetry, [switch]$Dashboard, [switch]$DashboardPreview, [switch]$PlannerPreview, [switch]$AirportIndexCheck)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

# Prefer a full JDK on PATH, then JAVA_HOME, then the JDK supplied by VS Code's Java extension.
$compiler = Get-Command javac.exe -ErrorAction SilentlyContinue
$jdkBin = $null
if ($compiler) { $jdkBin = Split-Path -Parent $compiler.Source }
if (-not $jdkBin -and $env:JAVA_HOME -and (Test-Path -LiteralPath "$env:JAVA_HOME\bin\javac.exe")) {
    $jdkBin = Join-Path $env:JAVA_HOME 'bin'
}
if (-not $jdkBin) {
    $extensionRoot = Join-Path $env:USERPROFILE '.vscode\extensions'
    $javaExtensions = Get-ChildItem -LiteralPath $extensionRoot -Directory -Filter 'redhat.java-*' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending
    foreach ($extension in $javaExtensions) {
        $bundled = Get-ChildItem -LiteralPath $extension.FullName -Recurse -Filter javac.exe -File -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($bundled) { $jdkBin = $bundled.DirectoryName; break }
    }
}
if (-not $jdkBin) { throw 'A JDK is required. Install a JDK or the VS Code Java extension, or set JAVA_HOME.' }
$classes = Join-Path $PSScriptRoot 'build\alia\classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\alia') -Filter '*.java' -File | ForEach-Object { $_.FullName })
if ($Test) { $sources += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'tests\alia') -Filter '*.java' -File | ForEach-Object { $_.FullName }) }
& (Join-Path $jdkBin 'javac.exe') --release 25 -encoding UTF-8 -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'ALIA compilation failed.' }
if ($BuildOnly) { Write-Host 'ALIA compiled successfully.'; exit 0 }
if ($Test) { & (Join-Path $jdkBin 'java.exe') -cp $classes alia.AliaTests }
elseif ($Probe) { & (Join-Path $jdkBin 'java.exe') -cp $classes alia.AliaFlight --probe }
elseif ($Recover) { & (Join-Path $jdkBin 'java.exe') -cp $classes alia.AliaFlight --recover }
elseif ($DashboardPreview) { & (Join-Path $jdkBin 'java.exe') -cp $classes alia.DashboardPreview }
elseif ($PlannerPreview) { & (Join-Path $jdkBin 'java.exe') -cp $classes alia.PlannerPreview }
elseif ($AirportIndexCheck) { & (Join-Path $jdkBin 'java.exe') -cp $classes alia.PlannerPreview --index }
else {
    $appArgs = @()
    if ($Telemetry) { $appArgs += '--telemetry' }
    if ($Dashboard) { $appArgs += '--dashboard' }
    & (Join-Path $jdkBin 'java.exe') -cp $classes alia.AliaFlight $appArgs
}
exit $LASTEXITCODE
