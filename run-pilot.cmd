@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-alia.ps1" -Pilot %*
set "pilotExit=%ERRORLEVEL%"
if not "%pilotExit%"=="0" pause
exit /b %pilotExit%
