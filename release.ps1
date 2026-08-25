# Release pipeline: build, test, tag, push, and publish a GitHub release
# (APK + latest.json manifest that the in-app updater reads).
# Usage:  powershell -ExecutionPolicy Bypass -File release.ps1 "release notes here"
# Bump versionCode/versionName in app\build.gradle BEFORE running this.
param(
    [string]$Notes = ""
)
$ErrorActionPreference = 'Stop'
$proj = 'C:\Apps\WeeklyBudget'
$env:JAVA_HOME = 'C:\Godot\android\jdk'

$gradleFile = Get-Content "$proj\app\build.gradle" -Raw
if ($gradleFile -match 'versionCode\s+(\d+)') { $code = $Matches[1] } else { throw 'versionCode not found' }
if ($gradleFile -match 'versionName\s+"([^"]+)"') { $name = $Matches[1] } else { throw 'versionName not found' }
Write-Host "Releasing v$name (versionCode $code)"

& C:\Godot\android\gradle-8.9\bin\gradle.bat -p $proj assembleRelease :app:testReleaseUnitTest --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Build or tests failed' }
Copy-Item "$proj\app\build\outputs\apk\release\app-release.apk" "$proj\WeeklyBudget.apk" -Force

$manifest = [ordered]@{
    versionCode = [long]$code
    versionName = $name
    apk         = 'https://github.com/Strongpaw/weekly-budget/releases/latest/download/WeeklyBudget.apk'
    notes       = $Notes
} | ConvertTo-Json
[System.IO.File]::WriteAllText("$proj\latest.json", $manifest)

Set-Location $proj
git add -A
git commit -m "Release v$name"
if (-not $?) { Write-Host 'Nothing new to commit' }
git tag "v$name"
git push origin main --tags
& C:\Godot\tools\gh\bin\gh.exe release create "v$name" "$proj\WeeklyBudget.apk" "$proj\latest.json" --title "Weekly Budget v$name" --notes "$Notes"
Write-Host "Released: https://github.com/Strongpaw/weekly-budget/releases/tag/v$name"
