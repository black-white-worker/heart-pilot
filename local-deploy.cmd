@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\local-deploy.ps1" %*
exit /b %ERRORLEVEL%
