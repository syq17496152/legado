@echo off
:: EnableDelayedExpansion（2026-08-30 修复）：!APK_FOUND!/!VERIFY_OK! 延迟扩展缺失导致
:: libcronet.so 强制校验块（下方 L188 起）为死代码、从未真正执行
setlocal EnableDelayedExpansion

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
::    build-legado.bat debug - 3.26.082918     (test package with explicit version, 与正式包版本对齐)
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

:: Parse custom package name (2nd arg; "-" 或空 = 使用默认包名，为占位第3参版本号)
set "CUSTOM_APP_ID="
if not "%~2"=="" if /i not "%~2"=="-" set "CUSTOM_APP_ID=%~2"

:: Parse explicit version (3rd arg, e.g. 3.26.082918) - 保证双包同版本发版
set "APP_VERSION="
if not "%~3"=="" set "APP_VERSION=%~3"

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

:: Assemble optional Gradle -P flags (custom package name / explicit version)
set "P_FLAGS="
if not "%CUSTOM_APP_ID%"=="" set "P_FLAGS=%P_FLAGS% -PcustomAppId=%CUSTOM_APP_ID%"
if not "%APP_VERSION%"=="" set "P_FLAGS=%P_FLAGS% -PappVersion=%APP_VERSION%"

:: Build with optional Gradle project properties
if "%BUILD_TYPE%"=="release" (
    call "%PROJECT_DIR%\gradlew.bat" assembleAppRelease --no-daemon %P_FLAGS%
) else (
    call "%PROJECT_DIR%\gradlew.bat" assembleAppDebug --no-daemon %P_FLAGS%
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
    echo [ARTIFACT] %DIST_DIR%\%%~nxf
)

if "!APK_FOUND!"=="0" (
    echo   [WARN] APK not found in %APK_BUILD_DIR%, check build log.
)

:: ============================================================
:: libcronet.so 打包验证（强制）
:: 来源: 2026-07-30 用户决策，m3u8播放依赖Cronet Native引擎
:: 详见: docs/project-rules/package-naming.md "libcronet.so 打包强制规范"
:: 2026-08-30 修复三处潜在缺陷（启用延迟扩展后首次真正运行时暴露）:
::   1) Expand-Archive 不支持 .apk 扩展名 → 弃用解压方案
::   2) Expand-Archive 对 APK 内中日文 UTF-8 条目名崩溃（Illegal characters in path）
::      → 改用 .NET ZipFile.OpenRead 流式读取（不解压/无临时文件/支持 UTF-8 条目名）
::   3) 原命令单引号包裹 $env:TEMP 路径致 PowerShell 不展开（恒找不到 so）；
::      校验逻辑改为"任一包失败即失败"（原最后一包通过则整体通过）
::   4) cronet-bundled Maven 迁移后 so 带版本号（libcronet.151.x.x.x.so），
::      旧精确名 libcronet.so 永远匹配失败 → 改为 libcronet*.so 模式匹配
::   5) 校验范围改为本次构建产物（APK_BUILD_DIR）——dist 目录含迁移前动态下载
::      模式的历史归档包（本就无内置 so），不应参与本次门禁
:: ============================================================
if "!APK_FOUND!"=="1" (
    echo.
    echo ============================================================
    echo   Verifying libcronet.so in APK...
    echo ============================================================
    set "VERIFY_BAD=0"
    for %%f in ("%APK_BUILD_DIR%\*.apk") do (
        powershell -NoProfile -Command "Add-Type -AssemblyName System.IO.Compression.FileSystem; $z = [System.IO.Compression.ZipFile]::OpenRead('%%f'); $found = $z.Entries | Where-Object { $_.FullName -like 'lib/arm64-v8a/libcronet*.so' }; $z.Dispose(); if ($found) { exit 0 } else { exit 1 }" && (
            echo   [OK] %%~nxf: libcronet.so packed
        ) || (
            echo   [FAIL] %%~nxf: libcronet.so MISSING! m3u8 playback will fail!
            set "VERIFY_BAD=1"
        )
    )
    if "!VERIFY_BAD!"=="1" (
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
