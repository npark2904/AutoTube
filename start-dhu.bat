@echo off
setlocal
cd /d "%~dp0"

REM ADB Path Configuration
if exist "C:\Android\Sdk\platform-tools\adb.exe" set "PATH=C:\Android\Sdk\platform-tools;%PATH%"
if exist "%LOCALAPPDATA%\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe" set "PATH=%LOCALAPPDATA%\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools;%PATH%"

echo ========================================================
echo   AutoTube - Desktop Head Unit (DHU) Launcher
echo ========================================================
echo.

echo [1/3] Checking ADB device connection...
adb devices
echo.

echo [2/3] Forwarding port tcp:5277...
adb forward tcp:5277 tcp:5277
if %errorlevel% neq 0 (
    echo.
    echo [Warning] Failed to forward ADB port.
    echo Please make sure your phone is connected and USB debugging is enabled.
    echo.
    pause
    exit /b 1
)
echo Port 5277 forwarded successfully!
echo.

echo [3/3] Starting Desktop Head Unit (DHU)...
echo * Please ensure you tapped 'Start head unit server' in Android Auto developer settings on your phone.
echo.
cd /d "%~dp0tools\dhu"
start desktop-head-unit.exe
echo DHU window has been launched!
echo.
