@echo off
rem adb-compatible shim over raw protocol (used when 360 MobileSrv hijacks port 5037).
rem Point ADB_PATH to this file.
"%~dp0..\venv\Scripts\python.exe" "%~dp0adb360_cli.py" %*
