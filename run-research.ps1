param([Parameter(Position=0)][ValidateSet('all','audit','train','replay','collect','test','monitor','sensor-demo','sensor-live','sensor-test')][string]$Action='all')
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

if ($Action -like 'sensor-*') {
    $sensorArgs = @()
    if ($Action -eq 'sensor-live') { $sensorArgs += '-Live' }
    if ($Action -eq 'sensor-test') { $sensorArgs += '-Test' }
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'run-soar-demo.ps1') @sensorArgs
    exit $LASTEXITCODE
}

if ($Action -eq 'monitor') {
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'run-alia.ps1') -Pilot -BrakingShadow
    exit $LASTEXITCODE
}

$researchPython = $null
if ($env:CCAT_RESEARCH_PYTHON -and (Test-Path -LiteralPath $env:CCAT_RESEARCH_PYTHON)) { $researchPython = $env:CCAT_RESEARCH_PYTHON }
foreach ($name in @('python3.14.exe','python3.exe','python.exe')) {
    if ($researchPython) { break }
    $found = Get-Command $name -ErrorAction SilentlyContinue
    if ($found -and $found.Source -notlike '*\Microsoft\WindowsApps\*') { $researchPython = $found.Source }
}
if (-not $researchPython) {
    $uvPython = Join-Path $env:USERPROFILE '.local\bin\python3.14.exe'
    if (Test-Path -LiteralPath $uvPython) { $researchPython = $uvPython }
}
if (-not $researchPython) { throw 'Python 3.10+ is required. Set CCAT_RESEARCH_PYTHON to its executable if it is not on PATH. No third-party Python packages are required.' }

if ($Action -eq 'test') {
    & $researchPython -m unittest discover -s research/tests -v
    if ($LASTEXITCODE -ne 0) { throw 'Research pipeline tests failed.' }
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'run-alia.ps1') -Test
    if ($LASTEXITCODE -ne 0) { throw 'ALIA / shadow integration tests failed.' }
    exit 0
}
& $researchPython (Join-Path $PSScriptRoot 'research\tools\research_pipeline.py') $Action --root $PSScriptRoot
if ($LASTEXITCODE -ne 0) { throw 'Research command failed. Inspect the reported data/split error; no simulator connection was made.' }
