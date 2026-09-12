@echo off
REM ScreenMind Launcher for AquaScope
REM Runs the local Gemma 4-powered desktop AI memory server on http://localhost:7777

set SCRIPT_DIR=%~dp0
set BASE_DIR=%SCRIPT_DIR%..
set SCREENMIND_DIR=%BASE_DIR%\screenmind

cd /d "%SCREENMIND_DIR%"
set PYTHONPATH=%SCREENMIND_DIR%;%PYTHONPATH%

echo =================================================================
echo  Starting SMRITI ScreenMind Desktop AI Memory Server
echo  Web Dashboard: http://localhost:7777
echo  REST API:      http://localhost:7777/docs
echo =================================================================

python -m screenmind
if errorlevel 1 (
    echo.
    echo Failed to start ScreenMind. Make sure dependencies are installed:
    echo pip install -r "%SCREENMIND_DIR%\requirements.txt"
    pause
)
