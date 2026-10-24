@echo off
set PATH=%USERPROFILE%\.gemini\antigravity\tools\node;%PATH%
echo Building IRIS Remote for Amazfit T-Rex 3...
call zeus build
echo.
echo Build complete! Output packages (.zpk and .zab) are in the dist\ directory.
pause
