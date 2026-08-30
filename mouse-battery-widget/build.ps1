<#
  Build script - no Maven required.

  Steps:
    1. Download dependency JARs into lib\ (first run only)
    2. Compile src
    3. Unpack dependencies and produce a single runnable JAR (dist\MouseBatteryWidget.jar)
    4. Create dist\MouseBatteryWidget.vbs to launch it with no console window

  Usage:
    powershell -ExecutionPolicy Bypass -File build.ps1
    powershell -ExecutionPolicy Bypass -File build.ps1 -Run              # build then launch (resident)
    powershell -ExecutionPolicy Bypass -File build.ps1 -Debug            # build then run diagnostics
    powershell -ExecutionPolicy Bypass -File build.ps1 -InstallStartup   # copy to %LOCALAPPDATA% and run at logon
    powershell -ExecutionPolicy Bypass -File build.ps1 -RemoveStartup    # remove the logon entry
#>
param(
    [switch]$Run,
    [switch]$Debug,
    [switch]$InstallStartup,
    [switch]$RemoveStartup
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$lib = Join-Path $root 'lib'
$out = Join-Path $root 'build\classes'
$dist = Join-Path $root 'dist'
$jarName = 'MouseBatteryWidget.jar'

$appDir = Join-Path $env:LOCALAPPDATA 'MouseBatteryWidget'
$startupLnk = Join-Path ([Environment]::GetFolderPath('Startup')) 'MouseBatteryWidget.lnk'
$runKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
$runName = 'MouseBatteryWidget'   # <- this is the name shown in Task Manager > Startup apps

function Stop-RunningInstance {
    $procs = @(Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like '*MouseBatteryWidget*' })
    foreach ($p in $procs) {
        Stop-Process -Id $p.ProcessId -Force
        Write-Host "stopped running instance (pid $($p.ProcessId))"
    }
    if ($procs.Count -gt 0) { Start-Sleep -Milliseconds 800 }
}

# --- -RemoveStartup (no build needed) --------------------------------
if ($RemoveStartup) {
    $removed = $false
    if (Get-ItemProperty -Path $runKey -Name $runName -ErrorAction SilentlyContinue) {
        Remove-ItemProperty -Path $runKey -Name $runName -Force
        Write-Host "removed Run entry: $runKey\$runName"
        $removed = $true
    }
    # also drop the old Startup-folder shortcut if a previous version created one
    if (Test-Path $startupLnk) { Remove-Item $startupLnk -Force; Write-Host "removed: $startupLnk"; $removed = $true }
    if (-not $removed) { Write-Host "no startup entry found." }
    Stop-RunningInstance
    return
}

# A running instance keeps dist\*.jar locked; stop it before rebuilding.
Stop-RunningInstance

# --- locate JDK -----------------------------------------------------------
$javac = Get-Command javac -ErrorAction SilentlyContinue
$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $javac -or -not $java) {
    throw "JDK not found. Install JDK 17+ and put javac/java on PATH."
}
$jar = Join-Path (Split-Path $javac.Source) 'jar.exe'
Write-Host ("JDK: " + (Split-Path (Split-Path $javac.Source)))

# --- download dependencies ---------------------------------------------
$deps = [ordered]@{
    'hid4java-0.8.0.jar'      = 'https://repo1.maven.org/maven2/org/hid4java/hid4java/0.8.0/hid4java-0.8.0.jar'
    'slf4j-api-2.0.13.jar'    = 'https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar'
    'slf4j-simple-2.0.13.jar' = 'https://repo1.maven.org/maven2/org/slf4j/slf4j-simple/2.0.13/slf4j-simple-2.0.13.jar'
    'jna-5.14.0.jar'          = 'https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.14.0/jna-5.14.0.jar'
}
New-Item -ItemType Directory -Force -Path $lib | Out-Null
foreach ($name in $deps.Keys) {
    $path = Join-Path $lib $name
    if (-not (Test-Path $path)) {
        Write-Host "download: $name"
        Invoke-WebRequest -Uri $deps[$name] -OutFile $path
    }
}
$cp = ($deps.Keys | ForEach-Object { Join-Path $lib $_ }) -join ';'

# --- compile ------------------------------------------------------------
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path $out | Out-Null

$sources = Get-ChildItem -Recurse -Filter *.java (Join-Path $root 'src\main\java') | ForEach-Object { $_.FullName }
Write-Host ("compile: " + $sources.Count + " files")
& $javac.Source -encoding UTF-8 --release 17 -cp $cp -d $out @sources
if ($LASTEXITCODE -ne 0) { throw "compilation failed" }

Copy-Item (Join-Path $root 'src\main\resources\*') $out -Recurse -Force -ErrorAction SilentlyContinue

# --- unpack dependencies into one fat jar -----------------------------
Write-Host "unpacking dependencies..."
foreach ($name in $deps.Keys) {
    Push-Location $out
    & $jar xf (Join-Path $lib $name)
    Pop-Location
}
# strip signatures / stray descriptors so the jar starts cleanly
Get-ChildItem -Path (Join-Path $out 'META-INF') -Include *.SF, *.RSA, *.DSA -Recurse -ErrorAction SilentlyContinue | Remove-Item -Force
$mf = Join-Path $out 'META-INF\MANIFEST.MF'
if (Test-Path $mf) { Remove-Item $mf -Force }
Get-ChildItem -Path $out -Filter 'module-info.class' -Recurse -ErrorAction SilentlyContinue | Remove-Item -Force

New-Item -ItemType Directory -Force -Path $dist | Out-Null
$jarPath = Join-Path $dist $jarName
if (Test-Path $jarPath) { Remove-Item $jarPath -Force }

Push-Location $out
& $jar cfe $jarPath 'com.example.mousebattery.App' .
Pop-Location
if ($LASTEXITCODE -ne 0) { throw "jar creation failed" }
Write-Host ("created: " + $jarPath)

# --- VBS launcher (no console window) --------------------------------
# Resolves paths at run time so the dist\ folder can be moved/copied freely.
$javawGuess = Join-Path (Split-Path $java.Source) 'javaw.exe'
$javaw = if (Test-Path $javawGuess) { $javawGuess } else { 'javaw.exe' }
$vbsLines = @(
    "' Launch MouseBatteryWidget without a console window"
    'Set fso = CreateObject("Scripting.FileSystemObject")'
    'Set sh  = CreateObject("WScript.Shell")'
    'q = Chr(34)'
    'scriptDir = fso.GetParentFolderName(WScript.ScriptFullName)'
    'jarPath = fso.BuildPath(scriptDir, "MouseBatteryWidget.jar")'
    'javaw = "' + ($javaw -replace '"', '""') + '"'
    'If Not fso.FileExists(javaw) Then javaw = "javaw.exe"'
    'sh.Run q & javaw & q & " -jar " & q & jarPath & q, 0, False'
)
$vbsPath = Join-Path $dist 'MouseBatteryWidget.vbs'
Set-Content -Path $vbsPath -Value $vbsLines -Encoding ASCII
Write-Host ("created: " + $vbsPath + "  (double-click, or put in the Startup folder, for resident use)")

# --- -InstallStartup: copy to %LOCALAPPDATA% and register at logon ----
# Copies out of the (possibly WSL-hosted) repo so it launches reliably at logon.
# Uses an HKCU\...\Run value (not a Startup-folder shortcut) so Task Manager >
# Startup apps lists it under the name "MouseBatteryWidget", not "Windows Script Host".
if ($InstallStartup) {
    Stop-RunningInstance

    New-Item -ItemType Directory -Force -Path $appDir | Out-Null
    Copy-Item $jarPath (Join-Path $appDir $jarName) -Force
    Copy-Item $vbsPath (Join-Path $appDir 'MouseBatteryWidget.vbs') -Force
    $installedJar = Join-Path $appDir $jarName

    $javawExe = if (Test-Path $javawGuess) { $javawGuess } else { 'javaw.exe' }
    $runCmd = '"' + $javawExe + '" -jar "' + $installedJar + '"'

    # javaw.exe is a GUI binary -> no console window, no wrapper needed
    New-ItemProperty -Path $runKey -Name $runName -Value $runCmd -PropertyType String -Force | Out-Null

    # if a previous version left a Startup-folder shortcut, remove it to avoid double launch
    if (Test-Path $startupLnk) { Remove-Item $startupLnk -Force }

    # clear any "disabled by user" flag Task Manager may have set previously
    $approved = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\Run'
    if (Test-Path $approved) {
        Remove-ItemProperty -Path $approved -Name $runName -ErrorAction SilentlyContinue
    }

    Write-Host ("installed to  : " + $appDir)
    Write-Host ("logon entry   : " + $runKey + "\" + $runName)
    Write-Host ("               = " + $runCmd)
    Write-Host ("Task Manager  : Startup apps -> `"" + $runName + "`"")

    Start-Process -FilePath $javawExe -ArgumentList @('-jar', $installedJar)
    Write-Host "`nstarted. it will now also launch at every logon."
    Write-Host "after changing the code, run 'build.ps1 -InstallStartup' again to refresh the copy."
    return
}

# --- finish / run -----------------------------------------------------
if ($Debug) {
    Write-Host "`n--- diagnostics ---`n"
    & $java.Source -jar $jarPath --debug
}
elseif ($Run) {
    Write-Host "`n--- launch ---`n"
    & $javaw -jar $jarPath
}
else {
    Write-Host "`ndone."
    Write-Host ("  diagnostics    : java -jar `"" + $jarPath + "`" --debug")
    Write-Host ("  resident       : wscript `"" + $vbsPath + "`"")
    Write-Host ("  run at logon   : build.ps1 -InstallStartup")
}
