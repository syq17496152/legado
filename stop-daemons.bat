@echo off
:: ============================================================
::  Legado build daemon cleanup (stop-daemons.bat)
::  Stop leftover Gradle daemon + Kotlin daemon to free memory.
::  Run after ANY build (build-legado.bat / direct gradlew / IDE).
::  Usage: stop-daemons.bat
::  Spec: docs/project-flow/build-apk-guide.md section 4.10
:: ============================================================
setlocal
set "PROJECT_DIR=%~dp0"
set "GRADLE_USER_HOME=F:\gh"
echo ============================================================
echo   Stopping build daemons to release memory...
echo ============================================================
cd /d "%PROJECT_DIR%"
:: Stop Gradle daemon (also stops the Kotlin daemon it manages)
call gradlew.bat --stop >nul 2>&1
:: Fallback: force-kill this project's leftover Kotlin daemon
:: (filtered by marker path containing in-legado, avoid killing others)
powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*KotlinCompileDaemon*' -and $_.CommandLine -like '*in-legado*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }" 2>nul
echo [OK] Build daemons stopped.
exit /b 0