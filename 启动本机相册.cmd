@echo off
chcp 65001 >nul
if exist "%~dp0.venv\Scripts\python.exe" (
  "%~dp0.venv\Scripts\python.exe" -X utf8 "%~dp0tools\start_local.py"
) else (
  python -X utf8 "%~dp0tools\start_local.py"
)
echo.
pause
