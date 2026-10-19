# Build the tv-home launcher APK on Windows (equivalent of build-and-install.sh, no Gradle).
# Usage:  powershell -File build-windows.ps1            build + install + set as home
#         powershell -File build-windows.ps1 -BuildOnly build only
param([switch]$BuildOnly)

# Native tools print warnings to stderr; we rely on exit codes instead.
$ErrorActionPreference = 'Continue'
$launcher = $PSScriptRoot
$B = Join-Path $launcher 'build'

# --- locate tools -------------------------------------------------------------
$btRoot = "$env:TEMP\opencode\bt34"
$BT = (Get-ChildItem $btRoot -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'aapt2.exe') } | Select-Object -First 1).FullName
$jar = (Get-ChildItem "$env:TEMP\opencode\p34" -Recurse -Filter android.jar | Select-Object -First 1).FullName
$JAVA = (Get-ChildItem "$env:TEMP\opencode\jdk17" -Directory | Select-Object -First 1).FullName
$adb = "$env:TEMP\opencode\platform-tools\platform-tools\adb.exe"
$device = '192.168.15.111:5555'

if (-not $BT) { throw 'build-tools not found' }
if (-not $jar) { throw 'android.jar not found' }
Write-Output "tools: $BT"; Write-Output "jar: $jar"; Write-Output "jdk: $JAVA"

# --- build --------------------------------------------------------------------
if (Test-Path $B) { Remove-Item $B -Recurse -Force }
New-Item -ItemType Directory -Path "$B\gen","$B\classes","$B\dex" -Force | Out-Null

& "$BT\aapt2.exe" compile --dir (Join-Path $launcher 'res') -o "$B\res.zip" 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

& "$BT\aapt2.exe" link -o "$B\base.apk" -I $jar --manifest (Join-Path $launcher 'AndroidManifest.xml') `
  --java "$B\gen" -A (Join-Path $launcher 'assets') --min-sdk-version 28 --target-sdk-version 28 "$B\res.zip" 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

# --- asset paths: aapt2 on Windows stores them with backslashes; Android needs '/'.
# We normalize AFTER merging classes.dex, in a single archive-update session.

$javaFiles = @(Get-ChildItem "$launcher\src","$B\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& "$JAVA\bin\javac.exe" -nowarn --release 11 -classpath $jar -d "$B\classes" $javaFiles 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

$classFiles = (Get-ChildItem "$B\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName })
& "$BT\d8.bat" --min-api 28 --lib $jar --output "$B\dex" $classFiles 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Copy-Item "$B\base.apk" "$B\unsigned.apk" -Force
# merge classes.dex and fix asset paths in one update session
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$B\unsigned.apk", 'Update')
try {
  [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$B\dex\classes.dex", 'classes.dex') | Out-Null
  $bad = @($zip.Entries | Where-Object { $_.FullName -like '*\*' })
  foreach ($e in $bad) {
    $ms = New-Object System.IO.MemoryStream
    $st = $e.Open(); $st.CopyTo($ms); $st.Dispose()
    $bytes = $ms.ToArray(); $ms.Dispose()
    $name = $e.FullName -replace '\\', '/'
    $e.Delete()
    $n = $zip.CreateEntry($name, [System.IO.Compression.CompressionLevel]::Optimal)
    $os = $n.Open(); $os.Write($bytes, 0, $bytes.Length); $os.Dispose()
  }
  Write-Output "assets normalized: $(@($zip.Entries | Where-Object { $_.FullName -like '*\*' }).Count) bad left"
} finally { $zip.Dispose() }

& "$BT\zipalign.exe" -f 4 "$B\unsigned.apk" "$B\aligned.apk" 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

$ks = Join-Path $launcher 'debug.keystore'
if (-not (Test-Path $ks)) {
  & "$JAVA\bin\keytool.exe" -genkeypair -keystore $ks -storepass android -keypass android `
    -alias tvhome -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=tvhome' 2>&1 | Out-Null
}
if (-not (Test-Path $ks)) { throw 'keytool failed' }
& "$BT\apksigner.bat" sign --ks $ks --ks-pass pass:android --out "$B\tvhome.apk" "$B\aligned.apk" 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

$apk = Get-Item "$B\tvhome.apk"
Write-Output ("built {0} ({1} bytes)" -f $apk.FullName, $apk.Length)
if ($BuildOnly) { exit 0 }

# --- install over network adb -------------------------------------------------
& $adb connect $device | Out-Null
& $adb -s $device install -r "$B\tvhome.apk"
if ($LASTEXITCODE -ne 0) { throw 'install failed' }

& $adb -s $device shell pm grant com.mihir.tvhome android.permission.ACCESS_FINE_LOCATION
& $adb -s $device shell cmd package set-home-activity --user 0 com.mihir.tvhome/.MainActivity
& $adb -s $device shell am force-stop com.android.tv.settings
& $adb -s $device shell input keyevent 3
Write-Output 'Installed. Pressed Home.'
