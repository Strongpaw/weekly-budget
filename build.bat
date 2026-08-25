@echo off
rem Build the Weekly Budget release APK (signed with the shared debug keystore)
set JAVA_HOME=C:\Godot\android\jdk
call C:\Godot\android\gradle-8.9\bin\gradle.bat -p C:\Apps\WeeklyBudget assembleRelease --console=plain
if errorlevel 1 exit /b 1
copy /y "C:\Apps\WeeklyBudget\app\build\outputs\apk\release\app-release.apk" "C:\Apps\WeeklyBudget\WeeklyBudget.apk" >nul
echo.
echo APK ready: C:\Apps\WeeklyBudget\WeeklyBudget.apk
