@echo off
set PATH=C:\Program Files\nodejs;%USERPROFILE%\.gemini\antigravity\tools\node;%PATH%
echo ========================================================
echo   IRIS Remote - Wireless Install for Amazfit T-Rex 3
echo ========================================================
echo.
echo 1. Ensure your phone and PC are on the same Wi-Fi network.
echo 2. Open the Zepp app on your phone:
echo    Profile -^> Amazfit T-Rex 3 -^> Developer Mode.
echo 3. Tap the Scan (QR code) icon in the top right.
echo 4. Scan the QR code that appears in this window below:
echo.
call zeus preview -t "Amazfit T-Rex 3"
pause
