param([switch]$Live,[switch]$Test,[switch]$Render,[switch]$InstallDependencies)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$jdkBin = $null
$compiler = Get-Command javac.exe -ErrorAction SilentlyContinue
if ($compiler) { $jdkBin = Split-Path -Parent $compiler.Source }
if (-not $jdkBin -and $env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\javac.exe")) { $jdkBin = Join-Path $env:JAVA_HOME 'bin' }
if (-not $jdkBin) {
    foreach ($extension in (Get-ChildItem (Join-Path $env:USERPROFILE '.vscode\extensions') -Directory -Filter 'redhat.java-*' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending)) {
        $found = Get-ChildItem $extension.FullName -Recurse -Filter javac.exe -File | Select-Object -First 1
        if ($found) { $jdkBin = $found.DirectoryName; break }
    }
}
if (-not $jdkBin) { throw 'JDK 25 is required (PATH, JAVA_HOME, or VS Code Java extension).' }
$lock = Get-Content 'research\config\soar-runtime-lock.json' -Raw | ConvertFrom-Json
$deps = Join-Path $PSScriptRoot 'research\local\deps'
New-Item -ItemType Directory -Path $deps -Force | Out-Null
$jars = @()
foreach ($artifact in $lock.artifacts) {
    $jar = Join-Path $deps (Split-Path $artifact.path -Leaf)
    if (-not (Test-Path -LiteralPath $jar)) {
        $cached = Join-Path (Join-Path $env:USERPROFILE '.m2\repository') $artifact.path
        if (Test-Path -LiteralPath $cached) { Copy-Item -LiteralPath $cached -Destination $jar }
        elseif ($InstallDependencies) { Invoke-WebRequest -UseBasicParsing -Uri ($lock.repository+'/'+$artifact.path) -OutFile $jar }
        else { throw "Missing dependency $($artifact.path). Run run-soar-demo.cmd -InstallDependencies once to download the pinned runtime." }
    }
    if ((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLower() -ne $artifact.sha256) { throw "Dependency checksum mismatch: $jar" }
    $jars += $jar
}
$classes = Join-Path $PSScriptRoot 'build\soar-sensor\classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sources = @(Get-ChildItem 'src\alia','src\research\alia' -Filter '*.java' -File | ForEach-Object FullName)
$classpath = (@($classes)+$jars) -join ';'
& (Join-Path $jdkBin 'javac.exe') --release 25 -encoding UTF-8 -cp $classpath -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Sensor research compilation failed.' }
$main = 'alia.SoarSensorDemo'
$appArgs = @()
if ($Test) { $main = 'alia.SoarSensorTests' }
elseif ($Render) { $appArgs += '--render' }
elseif ($Live) { $main = 'alia.AliaFlight'; $appArgs += '--pilot','--sensor-lab' }
$commit='UNKNOWN'
$dirty='UNKNOWN'
$git = Get-Command git.exe -ErrorAction SilentlyContinue
$gitPath = if ($git) { $git.Source } else { (Get-ChildItem (Join-Path $env:LOCALAPPDATA 'GitHubDesktop\app-*\resources\app\git\cmd\git.exe') -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1).FullName }
if ($gitPath) {
    $commit=(& $gitPath --no-optional-locks rev-parse HEAD 2>$null | Out-String).Trim()
    $dirty=[string][bool]((& $gitPath --no-optional-locks status --porcelain 2>$null | Out-String).Trim())
}
& (Join-Path $jdkBin 'java.exe') --enable-native-access=ALL-UNNAMED "-Dccat.research.commit=$commit" "-Dccat.research.dirty=$dirty" -cp $classpath $main $appArgs
if ($LASTEXITCODE -ne 0) { throw 'Sensor research command failed.' }
