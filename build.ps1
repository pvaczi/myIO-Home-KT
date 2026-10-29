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

$gradleArgs = @('-p', $proj, ':app:testDebugUnitTest', $task, '--console=plain')
if (-not $env:SECRET_RUN -and (Get-Command secret-run -ErrorAction SilentlyContinue)) {
  # Signing properties come from the machine vault (other/gradle-properties) as ORG_GRADLE_PROJECT_* env
  # (Gradle maps them to project properties); passwords masked, alias/store path not secret (`+`).
  # No vault entry yet -> secret-run exits 2; fall back to ~/.gradle/gradle.properties (until plan step [6]).
  $p = 'other/gradle-properties'
  & secret-run "ORG_GRADLE_PROJECT_VACZI_STORE_PASSWORD=$p#VACZI_STORE_PASSWORD" "ORG_GRADLE_PROJECT_VACZI_KEY_PASSWORD=$p#VACZI_KEY_PASSWORD" `
    "+ORG_GRADLE_PROJECT_VACZI_STORE_FILE=$p#VACZI_STORE_FILE" "+ORG_GRADLE_PROJECT_VACZI_KEY_ALIAS=$p#VACZI_KEY_ALIAS" `
    -- "$proj\gradlew.bat" @gradleArgs
  if ($LASTEXITCODE -eq 2) { Write-Warning 'secret-run exit 2 (vault/entry problem) - retrying without vault'; & "$proj\gradlew.bat" @gradleArgs }
} else {
  & "$proj\gradlew.bat" @gradleArgs
}
$rc = $LASTEXITCODE
# AGP 9 removed the variant-output rename API: the descriptive APK name is produced here, by copying.
if ($rc -eq 0 -and $Apk) {
  $dir = "$proj\app\build\outputs\apk\release"
  Copy-Item "$dir\app-release.apk" "$dir\myIO-Home-KT-release.apk" -Force
}
exit $rc