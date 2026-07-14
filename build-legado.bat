@echo off
setlocal

:: ============================================================
::  Legado APK Build Script
::  Usage: build-legado.bat [debug|release|clean] [package_name]
::
::  Package Types:
::  1. Test Package (测试包):
::     - Package: io.legado.app.debug
::     - Usage: Development, quick verification
::     - Command: build-legado.bat
::
::  2. Coexist Package (共存包):
::     - Package: Custom package (e.g., com.my.legado.debug)
::     - Usage: Coexist with official version
::     - Command: build-legado.bat debug com.my.legado
::
::  3. Release Package (正式包):
::     - Package: io.legado.app.release
::     - Usage: Production release
::     - Command: build-legado.bat release
::
::  Examples:
::    build-legado.bat                          (test package, default)
::    build-legado.bat release                  (release package, default)
::    build-legado.bat debug com.my.legado      (coexist package, custom)
::    build-legado.bat release com.my.legado    (coexist package, custom)
::    build-legado.bat clean
:: ============================================================

:: ---------- Config ----------
set "JAVA_HOME=C:\Program Files\AdoptOpenJDK\jdk-17.0.0.20-hotspot"
set "ANDROID_HOME=F:\myself\github\WeAgentChat\temp\legado\temp\android-sdk"
set "PROJECT_DIR=F:\myself\github\WeAgentChat\temp\legado"
set "APK_OUTPUT_DIR=%PROJECT_DIR%\app\build\outputs\apk"
set "GRADLE_USER_HOME=F:\gh"
set "DEFAULT_APP_ID=io.legado.missapp"
:: ----------------------------

if /i "%~1"=="clean" goto DO_CLEAN

:: Parse build type
set "BUILD_TYPE=debug"
if /i "%~1"=="release" set "BUILD_TYPE=release"
if /i "%~1"=="-r" set "BUILD_TYPE=release"

:: Parse custom package name (2nd arg)
set "CUSTOM_APP_ID="
if not "%~2"=="" set "CUSTOM_APP_ID=%~2"

:: Determine final applicationId
if "%CUSTOM_APP_ID%"=="" (
    set "FINAL_APP_ID=%DEFAULT_APP_ID%"
    set "APP_ID_MODE=default"
) else (
    set "FINAL_APP_ID=%CUSTOM_APP_ID%"
    set "APP_ID_MODE=custom"
)

echo ============================================================
echo   Legado APK Builder
echo ============================================================
echo   Build type : %BUILD_TYPE%
echo   Package ID : %FINAL_APP_ID% (%APP_ID_MODE%)
echo ============================================================
echo.

:: Check JDK
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] JDK not found: %JAVA_HOME%
    pause
    exit /b 1
)
echo [OK] JDK: %JAVA_HOME%

:: Check Android SDK
if not exist "%ANDROID_HOME%\platforms\android-36" (
    echo [ERROR] Android SDK not found: %ANDROID_HOME%
    pause
    exit /b 1
)
echo [OK] Android SDK: %ANDROID_HOME%

:: Check project
if not exist "%PROJECT_DIR%\gradlew.bat" (
    echo [ERROR] gradlew.bat not found in: %PROJECT_DIR%
    pause
    exit /b 1
)
echo [OK] Project: %PROJECT_DIR%
echo.

:: Clean Kotlin daemon cache (fix AccessDeniedException)
if exist "%LOCALAPPDATA%\kotlin\daemon" (
    echo [CLEAN] Removing Kotlin daemon cache...
    rd /s /q "%LOCALAPPDATA%\kotlin\daemon" 2>nul
)

:: Stop stale Gradle daemons
echo [CLEAN] Stopping stale Gradle daemons...
cd /d "%PROJECT_DIR%"
call gradlew.bat --stop >nul 2>&1

:: Ensure gradle-home dir exists
if not exist "%GRADLE_USER_HOME%" mkdir "%GRADLE_USER_HOME%"

:: Clean old transforms cache (fix cross-drive / long-path issues)
if exist "%GRADLE_USER_HOME%\caches\8.14.4\transforms" (
    echo [CLEAN] Removing old transforms cache...
    rd /s /q "%GRADLE_USER_HOME%\caches\8.14.4\transforms" 2>nul
)
if exist "C:\Users\%USERNAME%\.gradle\caches\8.14.4\transforms" (
    echo [CLEAN] Removing C-drive transforms cache...
    rd /s /q "C:\Users\%USERNAME%\.gradle\caches\8.14.4\transforms" 2>nul
)

echo.
echo ============================================================
echo   Building %BUILD_TYPE% APK...
echo   applicationId = %FINAL_APP_ID%
echo ============================================================
echo.

:: Build with optional custom applicationId
if "%CUSTOM_APP_ID%"=="" (
    :: Default package name - no -P flag needed
    if "%BUILD_TYPE%"=="release" (
        call gradlew.bat assembleAppRelease --no-daemon
    ) else (
        call gradlew.bat assembleAppDebug --no-daemon
    )
) else (
    :: Custom package name via Gradle project property
    if "%BUILD_TYPE%"=="release" (
        call gradlew.bat assembleAppRelease --no-daemon -PcustomAppId=%CUSTOM_APP_ID%
    ) else (
        call gradlew.bat assembleAppDebug --no-daemon -PcustomAppId=%CUSTOM_APP_ID%
    )
)

if errorlevel 1 (
    echo.
    echo ============================================================
    echo   BUILD FAILED!
    echo ============================================================
    echo.
    echo   Try: build-legado.bat clean
    echo.
    pause
    exit /b 1
)

echo.
echo ============================================================
echo   BUILD SUCCESS!
echo ============================================================
echo   Package: %FINAL_APP_ID%
echo ============================================================
echo.

set "APK_FOUND=0"
for /r "%APK_OUTPUT_DIR%" %%f in (*.apk) do (
    echo   %%f
    set "APK_FOUND=1"
)

if "%APK_FOUND%"=="0" (
    echo   [WARN] APK not found, check build log.
)

echo.
pause
exit /b 0

:DO_CLEAN
echo ============================================================
echo   Cleaning...
echo ============================================================
cd /d "%PROJECT_DIR%"
call gradlew.bat clean
echo.
echo   Done. Run: build-legado.bat [debug^|release] [package_name]
echo.
pause
exit /b 0
