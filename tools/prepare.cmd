@echo off
setlocal
set "PROJECT=%~dp0"
for %%I in ("%PROJECT%..") do set "SESSION=%%~fI"
set "TEMP=%SESSION%\.tmp"
set "TMP=%TEMP%"
if not exist "%TEMP%" mkdir "%TEMP%"
if not exist "%SESSION%\.gradle-dist" mkdir "%SESSION%\.gradle-dist"
if not exist "%SESSION%\.gradle-user-home" mkdir "%SESSION%\.gradle-user-home"
if not exist "%SESSION%\.java-home" mkdir "%SESSION%\.java-home"
set "ZIP=%SESSION%\.gradle-dist\gradle-9.2.1-bin.zip"
if exist "%SESSION%\.gradle-dist\gradle-9.2.1\bin\gradle.bat" (
  echo Gradle already present.
  exit /b 0
)
echo Downloading Gradle 9.2.1 ...
curl.exe -L --fail --retry 3 --retry-delay 2 -o "%ZIP%" "https://services.gradle.org/distributions/gradle-9.2.1-bin.zip"
if errorlevel 1 (
  echo Download failed.
  exit /b 1
)
echo Extracting ...
tar.exe -xf "%ZIP%" -C "%SESSION%\.gradle-dist"
if errorlevel 1 (
  echo Extraction failed.
  exit /b 1
)
echo Gradle ready.
exit /b 0
