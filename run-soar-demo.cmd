@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-soar-demo.ps1" %*
set "sensorExit=%ERRORLEVEL%"
if not "%sensorExit%"=="0" pause
exit /b %sensorExit%
