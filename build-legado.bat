@echo off
setlocal

:: ============================================================
::  Legado APK Build Script
::  Usage: build-legado.bat [debug|release|clean] [package_name]
::
::  Package Types:
::  1. Test Package (测试包):
::     - Package: io.legado.miss.app.debug
::     - Usage: Development, quick verification
::     - Command: build-legado.bat
::
::  2. Coexist Package (共存包):
::     - Package: io.legado.app.debug
::     - Usage: Coexist with official legado-E version
::     - Command: build-legado.bat debug io.legado.app
::
::  3. Release Package (正式包):
::     - Package: io.legado.miss.app.release
::     - Usage: Production release
::     - Command: build-legado.bat release
::
::  Examples:
::    build-legado.bat                          (test package, default)
::    build-legado.bat release                  (release package, default)
::    build-legado.bat debug io.legado.app     (coexist package, with original legado-E)
::    build-legado.bat release io.legado.app   (coexist package, with original legado-E)
::    build-legado.bat clean
:: ============================================================

:: ---------- Config ----------
set "JAVA_HOME=C:\Program Files\AdoptOpenJDK\jdk-17.0.0.20-hotspot"
set "ANDROID_HOME=C:\Android\Sdk"
set "PROJECT_DIR=F:\myself\github\WeAgentChat\temp\legado"
set "APK_OUTPUT_DIR=%PROJECT_DIR%\app\build\outputs\apk"
set "GRADLE_USER_HOME=F:\gh"
set "DEFAULT_APP_ID=io.legado.miss.app"
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
call "%PROJECT_DIR%\gradlew.bat" --stop >nul 2>&1

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
        call "%PROJECT_DIR%\gradlew.bat" assembleAppRelease --no-daemon
    ) else (
        call "%PROJECT_DIR%\gradlew.bat" assembleAppDebug --no-daemon
    )
) else (
    :: Custom package name via Gradle project property
    if "%BUILD_TYPE%"=="release" (
        call "%PROJECT_DIR%\gradlew.bat" assembleAppRelease --no-daemon -PcustomAppId=%CUSTOM_APP_ID%
    ) else (
        call "%PROJECT_DIR%\gradlew.bat" assembleAppDebug --no-daemon -PcustomAppId=%CUSTOM_APP_ID%
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
    call :STOP_DAEMON
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
:: 根据包类型确定子目录名：customAppId=coexist, release=release, debug=test
set "APK_SUBDIR=test"
if "%BUILD_TYPE%"=="release" set "APK_SUBDIR=release"
if not "%CUSTOM_APP_ID%"=="" set "APK_SUBDIR=coexist"
set "DIST_DIR=%PROJECT_DIR%\output\apk\%APK_SUBDIR%"
set "APK_BUILD_DIR=%APK_OUTPUT_DIR%\app\%BUILD_TYPE%"
if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"

for %%f in ("%APK_BUILD_DIR%\*.apk") do (
    echo   %%f
    set "APK_FOUND=1"
    copy /y "%%f" "%DIST_DIR%\" >nul 2>&1
    echo   [COPY] %%f -^> %DIST_DIR%\
)

if "!APK_FOUND!"=="0" (
    echo   [WARN] APK not found in %APK_BUILD_DIR%, check build log.
)

:: ============================================================
:: libcronet.so 打包验证（强制）
:: 来源: 2026-07-30 用户决策，m3u8播放依赖Cronet Native引擎
:: 详见: docs/project-rules/package-naming.md "libcronet.so 打包强制规范"
:: ============================================================
if "!APK_FOUND!"=="1" (
    echo.
    echo ============================================================
    echo   Verifying libcronet.so in APK...
    echo ============================================================
    set "VERIFY_OK=0"
    for %%f in ("%DIST_DIR%\*.apk") do (
        powershell -NoProfile -Command "Expand-Archive -Path '%%f' -DestinationPath $env:TEMP\apk_check -Force; $so = Get-ChildItem -Path '$env:TEMP\apk_check\lib\arm64-v8a\libcronet.so' -ErrorAction SilentlyContinue; if ($so) { Write-Host '[OK] %%~nxf: libcronet.so packed' -ForegroundColor Green; exit 0 } else { Write-Host '[FAIL] %%~nxf: libcronet.so MISSING! m3u8 playback will fail!' -ForegroundColor Red; exit 1 }" && (
            set "VERIFY_OK=1"
        ) || (
            set "VERIFY_OK=0"
        )
        powershell -NoProfile -Command "Remove-Item -Path $env:TEMP\apk_check -Recurse -Force -ErrorAction SilentlyContinue"
    )
    if "!VERIFY_OK!"=="0" (
        echo.
        echo ============================================================
        echo   [FAIL] libcronet.so verification failed!
        echo   m3u8 playback will NOT work. Do not release this APK.
        echo   Check: app\src\main\jniLibs\arm64-v8a\libcronet.so
        echo ============================================================
        pause
        exit /b 1
    )
)

call :STOP_DAEMON

echo.
pause
exit /b 0

:: ============================================================
::  Stop build daemons after packaging to free memory
::  (fix 2026-08-21: --no-daemon does NOT stop Kotlin daemon;
::   Gradle/Kotlin daemons auto-shutdown only after 2-3h idle)
:: ============================================================
:STOP_DAEMON
echo.
echo ============================================================
echo   Stopping build daemons to release memory...
echo ============================================================
cd /d "%PROJECT_DIR%"
:: Stop Gradle daemon (also stops the Kotlin daemon it manages)
call "%PROJECT_DIR%\gradlew.bat" --stop >nul 2>&1
:: Fallback: force-kill this project's leftover Kotlin daemon
:: (filtered by marker path containing in-legado, avoid killing others)
powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*KotlinCompileDaemon*' -and $_.CommandLine -like '*in-legado*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }" 2>nul
echo [OK] Build daemons stopped.
exit /b 0

:DO_CLEAN
echo ============================================================
echo   Cleaning...
echo ============================================================
cd /d "%PROJECT_DIR%"
call "%PROJECT_DIR%\gradlew.bat" clean
echo.
echo   Done. Run: build-legado.bat [debug^|release] [package_name]
echo.
pause
exit /b 0
