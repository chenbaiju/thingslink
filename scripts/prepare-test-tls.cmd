@echo off
setlocal
set "ROOT=%~dp0.."
set "TLSJAVA=java"
if defined JAVA_HOME set "TLSJAVA=%JAVA_HOME%\bin\java.exe"
"%TLSJAVA%" "%ROOT%\things-link\things-link-testing\src\main\java\com\things\link\testing\tls\TestTlsMaterial.java" "%ROOT%"
exit /b %ERRORLEVEL%
