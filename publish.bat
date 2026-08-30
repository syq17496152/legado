@echo off
setlocal
:: ============================================================
::  publish.bat - One-click release entry (thin shell)
::  Usage: publish.bat [--version 3.26.0901] [--dry-run]
::                      [--platform gitee^|github^|both]
::                      [--confirm-stage build|tag] [--l2-evidence <path>]
::
::  Five stages: version confirm -> 3 APK build -> verify fail-fast
::               -> gh release -> git tag
::  Confirm points: before build / L2 device gate (default N) / tag push
::  Detail: docs/project-rules/apk-publish-workflow.md
:: ============================================================
set "PY=%~dp0ai_tests\venv\Scripts\python.exe"
if not exist "%PY%" (
    echo [ERROR] Project venv python not found: %PY%
    pause
    exit /b 2
)
"%PY%" "%~dp0scripts\publish_release.py" %*
set "EC=%ERRORLEVEL%"
echo.
pause
exit /b %EC%
