@echo off
rem Installs the newest built jar into the local simmc instance.
rem add -Wait to park the installation until Minecraft exits (run it as a background job)
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy.ps1" %*
exit /b %ERRORLEVEL%
