@echo off
setlocal
if not defined LOCALAPPDATA (
  echo Windows LOCALAPPDATA is required for private simulation state.
  pause
  exit /b 64
)
"%~dp0PocketPhotoBridge.exe" --make-bootstrap "--state-dir=%LOCALAPPDATA%\PocketPhotoBridge\service-simulation"
if errorlevel 1 (
  pause
  exit /b 1
)
echo Open http://127.0.0.1:8080/ and use the administrator password shown above.
echo Keep this window open. Press Ctrl+C to stop the simulation.
"%~dp0PocketPhotoBridge.exe" --simulate-service "--state-dir=%LOCALAPPDATA%\PocketPhotoBridge\service-simulation"
set "serviceExit=%errorlevel%"
pause
exit /b %serviceExit%
