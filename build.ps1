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
exit $LASTEXITCODE
