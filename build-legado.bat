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
if /i "%~1"=="daemon-stop" goto DO_DAEMON_STOP

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

:: 2026-09-03 local-build-speedup（P1 daemon 复用）：
:: 已移除"每次构建前删除 Kotlin daemon 缓存 + gradlew --stop"逻辑——该逻辑导致
:: Kotlin 增量编译快照每次丢失、compileAppDebugKotlin 准全量重编（增量打包 7m33s 实测根因）。
:: 内存安全由三重保险替代：jvmargs Xmx 限幅 + idletimeout 600000 空闲自退（连带回收 Kotlin daemon）
:: + daemon-stop 手动清场入口。Kotlin daemon 缓存损坏时手动执行:
::   build-legado.bat daemon-stop
::   rd /s /q "%LOCALAPPDATA%\kotlin\daemon"
echo [SKIP] Keeping daemons alive for incremental build (daemon-stop to force cleanup)
echo [MEM-BEFORE]
powershell -NoProfile -Command "$os=Get-CimInstance Win32_OperatingSystem; $t=[math]::Round($os.TotalVisibleMemorySize/1MB,2); $f=[math]::Round($os.FreePhysicalMemory/1MB,2); $j=(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Measure-Object WorkingSetSize -Sum).Sum/1GB; Write-Host ('  system {0}pct used ({1:0.0}/{2:0.0}GB) | java RSS total {3:0.00}GB' -f [math]::Round(($t-$f)/$t*100,1),($t-$f),$t,[math]::Round($j,2))" 2>nul

:: Ensure gradle-home dir exists
if not exist "%GRADLE_USER_HOME%" mkdir "%GRADLE_USER_HOME%"

:: NOTE: 已移除"每次构建前删除 transforms 缓存"逻辑（2026-09-01）
:: 原逻辑导致：①每次构建全量重解压依赖（5-10 分钟）②删除后 Gradle 立即重建时
:: metadata.bin 读取竞态失败（Could not read workspace metadata，实测 f67443882 反复损坏）
:: 缓存损坏时手动执行: rd /s /q "%GRADLE_USER_HOME%\caches\8.14.4\transforms"
echo [SKIP] Keeping transforms cache (incremental build)

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

:: Build with optional Gradle project properties + transient-lock auto-retry
:: 2026-09-03 local-build-speedup（P1/P1b）：
:: ① 移除 --no-daemon：复用 daemon 保住 Kotlin 增量编译快照（VFS/配置缓存同步生效）
:: ② debug 分支注入降堆参数（红队 H5：-D 覆盖会整体替换 properties 参数串，
::    必须完整复制原串仅改 Xmx 4g→3g）；release 不注入，沿用 properties 4g（R8 OOM 防回归）
:: 2026-09-15 cronet-dynamic-download 增强：transform 瞬态锁自动重试——
::   官方 so/jar 大文件频繁进出 transforms 缓存后，Defender/TGitCache 与 Gradle 的
::   rename 竞争导致 "Could not move temporary workspace" 瞬态失败（实测 10-30s 内快速失败）。
::   策略：失败且耗时 <120s 判定为瞬态锁 → 自动 gradlew --stop 清场重试（最多 3 次，
::   UP-TO-DATE 保住已完成任务，重试成本低）；真实编译/R8 错误耗时 >120s → 立即失败不浪费时间
set "HEAP_ARGS="
if "%BUILD_TYPE%"=="debug" (
set HEAP_ARGS=-Dorg.gradle.jvmargs="-XX:+UseParallelGC -Xmx3g -Xms256m -XX:MaxMetaspaceSize=768m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8" -Dkotlin.daemon.jvmargs="-Xmx3g -XX:MaxMetaspaceSize=768m"
)

set "BUILD_TASK=assembleAppDebug"
if "%BUILD_TYPE%"=="release" set "BUILD_TASK=assembleAppRelease"

set /a ATTEMPT=0
set /a MAX_ATTEMPTS=3

:BUILD_LOOP
set /a ATTEMPT+=1
set "T0=%TIME: =0%"
call "%PROJECT_DIR%\gradlew.bat" %BUILD_TASK% %HEAP_ARGS% %P_FLAGS%
set "T1=%TIME: =0%"
set /a ELAPSED=((1%T1:~0,2%-100)*360000+(1%T1:~3,2%-100)*6000+(1%T1:~6,2%-100)*100+(1%T1:~9,2%-100)) - ((1%T0:~0,2%-100)*360000+(1%T0:~3,2%-100)*6000+(1%T0:~6,2%-100)*100+(1%T0:~9,2%-100))
if %ELAPSED% LSS 0 set /a ELAPSED+=8640000
if errorlevel 1 (
    if !ATTEMPT! LSS !MAX_ATTEMPTS! if !ELAPSED! LSS 12000 (
        echo.
        echo   [AUTO-RETRY !ATTEMPT!/!MAX_ATTEMPTS!] Fast failure (!ELAPSED!cs ^< 120s^) = transient transform-lock suspected.
        echo   Stopping daemons and retrying...
        echo.
        call "%PROJECT_DIR%\gradlew.bat" --stop >nul 2>&1
        goto BUILD_LOOP
    )
    echo.
    echo ============================================================
    echo   BUILD FAILED! ^(attempt !ATTEMPT!/!MAX_ATTEMPTS!, !ELAPSED!cs^)
    echo ============================================================
    echo.
    echo   Fast failure repeatedly = transform-lock contention persists.
    echo     Root fix: add F:\gh to Windows Defender exclusions (or exit TGitCache).
    echo   Slow failure = real compile/R8 error, see error lines above.
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
:: Cronet 动态下载打包验证（强制）[2026-09-15 cronet-dynamic-download 路线反转]
:: 2026-07-30 用户决策: m3u8播放依赖Cronet Native引擎 → 校验 so 必须进 APK
:: 2026-09-15 路线反转: so 运行时按 ABI 动态下载, 不再进 APK(减重~5-11MB) →
::   校验反转为两向门禁:
::     1) APK 内不得含 libcronet*.so (若存在=bundled 依赖泄漏回退, 包体异常)
::     2) APK 内必须含 assets/cronet.json (运行时下载 MD5 清单, 缺失=下载校验必失败)
:: 历史沿革: 2026-08-30 修复解压缺陷改 .NET 流式读取/cronet-bundled 迁移带版本号匹配/
::   校验范围限定本次构建产物; 2026-09-15 反转门禁语义
:: ============================================================
if "!APK_FOUND!"=="1" (
    echo.
    echo ============================================================
    echo   Verifying Cronet dynamic-download packaging...
    echo ============================================================
    set "VERIFY_BAD=0"
    for %%f in ("%APK_BUILD_DIR%\*.apk") do (
        powershell -NoProfile -Command "Add-Type -AssemblyName System.IO.Compression.FileSystem; $z = [System.IO.Compression.ZipFile]::OpenRead('%%f'); $so = $z.Entries | Where-Object { $_.FullName -like 'lib/*/libcronet*.so' }; $manifest = $z.Entries | Where-Object { $_.FullName -eq 'assets/cronet.json' }; $z.Dispose(); if (-not $so -and $manifest) { exit 0 } else { exit 1 }" && (
            echo   [OK] %%~nxf: no bundled so + cronet.json manifest present
        ) || (
            echo   [FAIL] %%~nxf: unexpected bundled libcronet*.so OR missing assets/cronet.json!
            set "VERIFY_BAD=1"
        )
    )
    if "!VERIFY_BAD!"=="1" (
        echo.
        echo ============================================================
        echo   [FAIL] Cronet dynamic-download packaging verification failed!
        echo   - libcronet*.so in APK = bundled dependency leaked (check build.gradle deps)
        echo   - assets/cronet.json missing = run gradlew app:downloadCronet --no-configuration-cache
        echo   Runtime downloads so by ABI; without manifest the download check always fails.
        echo ============================================================
        pause
        exit /b 1
    )
)

:: 2026-09-03 local-build-speedup：成功路径不再强制清场（原 call :STOP_DAEMON 已移除），
:: daemon 复用是增量提速核心；内存由 idletimeout 空闲自退回收，daemon-stop 可手动清场
echo [MEM-AFTER]
powershell -NoProfile -Command "$os=Get-CimInstance Win32_OperatingSystem; $t=[math]::Round($os.TotalVisibleMemorySize/1MB,2); $f=[math]::Round($os.FreePhysicalMemory/1MB,2); $j=(Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Measure-Object WorkingSetSize -Sum).Sum/1GB; Write-Host ('  system {0}pct used ({1:0.0}/{2:0.0}GB) | java RSS total {3:0.00}GB (daemon auto-exits after 10min idle; run daemon-stop to force cleanup)' -f [math]::Round(($t-$f)/$t*100,1),($t-$f),$t,[math]::Round($j,2))" 2>nul

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

:DO_DAEMON_STOP
echo ============================================================
echo   Manual daemon cleanup (local-build-speedup 2026-09-03)
echo ============================================================
call :STOP_DAEMON
echo.
echo   Daemons stopped. Kotlin daemon cache (if corrupted):
echo     rd /s /q "%LOCALAPPDATA%\kotlin\daemon"
echo.
pause
exit /b 0
