@echo off
setlocal EnableDelayedExpansion

rem ================================================================
rem  ThingsLink backend full verification: clean verify
rem  - console + file output at the same time (Tee)
rem  - incrementing log name verify-1.log / verify-2.log ... (no overwrite)
rem  - log dir: <repository root>/logs/backend  (logs/ is gitignored)
rem  - matches CI backend.yml: ./mvnw -B clean verify (-B = readable log)
rem ================================================================

for %%I in ("%~dp0..\..") do set "ROOT=%%~fI"
set "BACKEND=%ROOT%\things-link"
set "LOGDIR=%ROOT%\logs\backend"

if not exist "%LOGDIR%" mkdir "%LOGDIR%"

set /a N=1
:findlog
if exist "%LOGDIR%\verify-!N!.log" (
    set /a N+=1
    goto :findlog
)
set "LOG=%LOGDIR%\verify-!N!.log"

echo.
echo [verify] backend dir : %BACKEND%
echo [verify] log file   : %LOG%
echo [verify] starting clean verify (this may take several minutes) ...
echo.

if not exist "%BACKEND%" (
    echo [verify] ERROR: backend dir not found: %BACKEND%
    pause
    exit /b 1
)
cd /d "%BACKEND%"

powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "& { & '..\scripts\prepare-test-tls.cmd' 2>&1 | Tee-Object -FilePath '!LOG!'; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }; & .\mvnw.cmd -B clean verify 2>&1 | Tee-Object -Append -FilePath '!LOG!'; exit $LASTEXITCODE }"

set "EXIT=%ERRORLEVEL%"
echo.
if "%EXIT%"=="0" (
    echo [verify] BUILD SUCCESS - exit code 0
) else (
    echo [verify] BUILD FAILED - exit code %EXIT%
)
echo [verify] full log : %LOG%
echo.
pause
exit /b %EXIT%
