# build.ps1 — myIO Home KT release build (unit tests + signed release artifact)
#
# Why it exists: `java` is not on PATH, so every build needs JAVA_HOME set to the Android Studio JBR.
# Default: unit tests + bundleRelease (AAB for Play Console).
#   -Apk  : assembleRelease instead (sideload APK, install via Google Drive)
# Output:
#   app/build/outputs/bundle/release/app-release.aab
#   app/build/outputs/apk/release/myIO-Home-KT-release.apk

param(
  [switch]$Apk
)

$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$proj = $PSScriptRoot
$task = if ($Apk) { ':app:assembleRelease' } else { ':app:bundleRelease' }

& "$proj\gradlew.bat" -p $proj :app:testDebugUnitTest $task --console=plain
$rc = $LASTEXITCODE
# AGP 9 removed the variant-output rename API: the descriptive APK name is produced here, by copying.
if ($rc -eq 0 -and $Apk) {
  $dir = "$proj\app\build\outputs\apk\release"
  Copy-Item "$dir\app-release.apk" "$dir\myIO-Home-KT-release.apk" -Force
}
exit $rc