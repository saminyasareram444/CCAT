@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-alia.ps1" -Dashboard %*
set "aliaExit=%ERRORLEVEL%"
if not "%aliaExit%"=="0" pause
exit /b %aliaExit%
