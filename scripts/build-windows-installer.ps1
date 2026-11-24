[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ProjectDir,
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$OutputDir
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$jdk = if (-not [string]::IsNullOrWhiteSpace($env:JDK21_HOME)) { $env:JDK21_HOME } else { $JavaHome }
$releasePath = Join-Path $jdk 'release'
if (-not (Test-Path -LiteralPath $releasePath)) { throw "No se encuentra la instalación de JDK: $jdk" }
$releaseContent = Get-Content -LiteralPath $releasePath -Raw
if ($releaseContent -notmatch 'JAVA_VERSION="21\.') {
    throw "NeuroCenter necesita JDK 21 para empaquetar. JDK elegido: $jdk"
}
$jpackage = Join-Path $jdk 'bin\jpackage.exe'
if (-not (Test-Path -LiteralPath $jpackage)) { throw "JDK 21 no incluye jpackage.exe: $jdk" }

$isccCandidates = @()
if (-not [string]::IsNullOrWhiteSpace($env:INNO_SETUP_HOME)) {
    $isccCandidates += (Join-Path $env:INNO_SETUP_HOME 'ISCC.exe')
}
$isccCommand = Get-Command ISCC.exe -ErrorAction SilentlyContinue
if ($null -ne $isccCommand) { $isccCandidates += $isccCommand.Source }
$isccCandidates += "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe"
$isccCandidates += "$env:ProgramFiles\Inno Setup 6\ISCC.exe"
$iscc = $isccCandidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } |
    Select-Object -First 1
if (-not $iscc) {
    throw "Falta Inno Setup 6. Instálalo con 'choco install innosetup -y' o indica INNO_SETUP_HOME."
}

$jar = Join-Path $ProjectDir 'target\center-of-neurodivergence.jar'
if (-not (Test-Path -LiteralPath $jar)) { throw "Falta el JAR ejecutable: $jar" }
$inputDir = Join-Path $ProjectDir 'target\jpackage-input'
$imageOutput = Join-Path $ProjectDir 'target\jpackage-image'
$imageDir = Join-Path $imageOutput 'Center of Neurodivergence'
New-Item -ItemType Directory -Force -Path $inputDir | Out-Null
if (Test-Path -LiteralPath $imageOutput) {
    Remove-Item -LiteralPath $imageOutput -Force -Recurse
}
Copy-Item -LiteralPath $jar -Destination (Join-Path $inputDir 'center-of-neurodivergence.jar') -Force

Write-Host "Creando lanzador y runtime privado Java 21 con $jpackage"
$jpackageArgs = @(
    '--type', 'app-image',
    '--name', 'Center of Neurodivergence',
    '--app-version', $Version,
    '--vendor', 'NeuroCenter',
    '--description', 'Centro de aplicaciones de neurodivergencia',
    '--input', $inputDir,
    '--main-jar', 'center-of-neurodivergence.jar',
    '--main-class', 'com.neurocenter.Main',
    '--java-options', '-Djava.security.manager=allow',
    '--dest', $imageOutput
)
& $jpackage @jpackageArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage falló con código $LASTEXITCODE" }
if (-not (Test-Path -LiteralPath (Join-Path $imageDir 'Center of Neurodivergence.exe'))) {
    throw "jpackage no creó el lanzador esperado."
}
if (-not (Test-Path -LiteralPath (Join-Path $imageDir 'runtime'))) {
    throw "jpackage no incluyó Java 21."
}

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
$iss = Join-Path $ProjectDir 'packaging\windows\NeuroCenter.iss'
if (-not (Test-Path -LiteralPath $iss)) { throw "Falta el archivo Inno Setup: $iss" }
Write-Host "Compilando asistente de instalación en español con $iscc"
& $iscc "/DAppVersion=$Version" "/DAppImage=$imageDir" "/O$OutputDir" $iss
if ($LASTEXITCODE -ne 0) { throw "Inno Setup falló con código $LASTEXITCODE" }
$expected = Join-Path $OutputDir "Center-of-Neurodivergence-Setup-$Version.exe"
if (-not (Test-Path -LiteralPath $expected)) {
    throw "Falta el instalador terminado: $expected"
}
Write-Host "Instalador terminado: $expected"
Write-Host "Incluye asistente, selector de carpeta, actualización y runtime Java 21."
