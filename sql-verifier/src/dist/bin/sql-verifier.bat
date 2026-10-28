@echo off
setlocal
set "APP_HOME=%~dp0.."
if defined JAVA_HOME (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_EXE=java.exe"
)
"%JAVA_EXE%" -Dfile.encoding=UTF-8 -cp "%APP_HOME%\lib\*;%APP_HOME%\drivers\*" com.kairui.tools.sqlverify.SqlVerifyMain %*
set "VERIFY_EXIT=%ERRORLEVEL%"
if "%VERIFY_EXIT%"=="9009" set "VERIFY_EXIT=1"
exit /b %VERIFY_EXIT%
