@echo off
setlocal
set "PROJECT=%~dp0"
for %%I in ("%PROJECT%..") do set "SESSION=%%~fI"
set "GRADLE_USER_HOME=%SESSION%\.gradle-user-home"
set "TEMP=%SESSION%\.tmp"
set "TMP=%TEMP%"
if not exist "%TEMP%" mkdir "%TEMP%"
if not exist "%SESSION%\.java-home" mkdir "%SESSION%\.java-home"
if not exist "%GRADLE_USER_HOME%" mkdir "%GRADLE_USER_HOME%"
set "JAVA_TOOL_OPTIONS=%JAVA_TOOL_OPTIONS% -Duser.home="%SESSION%\.java-home" -Djava.io.tmpdir="%TEMP%" -Dfile.encoding=UTF-8"
set "GRADLE_BIN=%SESSION%\.gradle-dist\gradle-9.2.1\bin\gradle.bat"
if not exist "%GRADLE_BIN%" (
  echo Gradle distribution missing. Run tools\prepare.cmd first.
  exit /b 1
)
call "%GRADLE_BIN%" -p "%PROJECT%." --no-daemon --console=plain %*
exit /b %ERRORLEVEL%
