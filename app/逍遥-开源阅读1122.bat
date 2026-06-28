@echo off
chcp 65001 >nul
title WebService Starter

:: ====================== Config ======================
set "MEMU_INSTALL_PATH=D:\Program Files\Microvirt\MEmu"
set "ADB_CONNECT_ADDR=127.0.0.1:21503"
set "LOCAL_HTTP_PORT=1122"
set "ANDROID_HTTP_PORT=1122"
set "LOCAL_WS_PORT=1123"
set "ANDROID_WS_PORT=1123"
set "APP_PACKAGE=io.legado.app.release"
set "WEB_SERVICE=%APP_PACKAGE%/%APP_PACKAGE%.service.WebService"
:: ====================================================

cd /d "%MEMU_INSTALL_PATH%" || (
    echo [ERROR] MEmu not found: %MEMU_INSTALL_PATH%
    pause
    exit /b 1
)

echo [1/6] Starting MEmu...
start "" "%MEMU_INSTALL_PATH%\MEmu.exe"

echo [2/6] Waiting for ADB connection...
:wait_connect
adb connect %ADB_CONNECT_ADDR% | findstr /i "connected already" >nul
if %errorlevel% neq 0 (
    timeout /t 3 /nobreak >nul
    goto wait_connect
)
echo ADB connected.

echo [3/6] Launching Legado app...
adb shell monkey -p %APP_PACKAGE% -c android.intent.category.LAUNCHER 1 >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Failed to launch app. Check package name.
    pause
    exit /b 1
)
echo App launched.

echo [4/6] Waiting for app init (3s)...
timeout /t 3 /nobreak >nul

echo [5/6] Starting WebService via adb...
:: Try foreground-service first (Android 8+), fallback to startservice
adb shell am start-foreground-service -n %WEB_SERVICE% >nul 2>&1
if %errorlevel% neq 0 (
    echo   foreground-service failed, trying startservice...
    adb shell am startservice -n %WEB_SERVICE% >nul 2>&1
)
:: Verify by checking if service is running
adb shell dumpsys activity services %APP_PACKAGE%/.service.WebService | findstr "ServiceRecord" >nul 2>&1
if %errorlevel% equ 0 (
    echo WebService started.
) else (
    echo [WARN] WebService may not be running. Try manual start in app.
)

echo Waiting for WebService ready (2s)...
timeout /t 2 /nobreak >nul

echo [6/6] Setting up port forwarding...
adb forward tcp:%LOCAL_HTTP_PORT% tcp:%ANDROID_HTTP_PORT%
set "ret1=%errorlevel%"
adb forward tcp:%LOCAL_WS_PORT% tcp:%ANDROID_WS_PORT%
set "ret2=%errorlevel%"

echo.
if %ret1% equ 0 (
    echo   HTTP  : http://127.0.0.1:%LOCAL_HTTP_PORT%  [OK]
) else (
    echo   HTTP  : port %LOCAL_HTTP_PORT% forward FAILED
)
if %ret2% equ 0 (
    echo   WS    : ws://127.0.0.1:%LOCAL_WS_PORT%  [OK]
) else (
    echo   WS    : port %LOCAL_WS_PORT% forward FAILED
)
echo.
echo Done!
pause
exit /b
