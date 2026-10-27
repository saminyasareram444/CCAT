@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-research.ps1" %*
set "researchExit=%ERRORLEVEL%"
if not "%researchExit%"=="0" pause
exit /b %researchExit%
