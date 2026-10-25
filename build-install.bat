@echo off
setlocal
cd /d "%~dp0"

echo ========================================================
echo   AutoTube - Building APK
echo ========================================================
echo.

set "JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
set "ANDROID_HOME=C:\Android\Sdk"
set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\cmdline-tools\latest\bin;%PATH%"

call "%~dp0gradlew.bat" assembleDebug

if %errorlevel% neq 0 (
    echo.
    echo [Error] Build failed!
    pause
    exit /b 1
)

echo.
echo ========================================================
echo   [SUCCESS] Build Complete!
echo   APK File: app\build\outputs\apk\debug\app-debug.apk
echo ========================================================
echo.


@echo off
setlocal
cd /d "%~dp0"

REM ADB Path Configuration
if exist "C:\Android\Sdk\platform-tools\adb.exe" set "PATH=C:\Android\Sdk\platform-tools;%PATH%"
if exist "%LOCALAPPDATA%\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe" set "PATH=%LOCALAPPDATA%\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools;%PATH%"

echo ========================================================
echo   AutoTube - Install App to Android Phone
echo ========================================================
echo.

set "APK_PATH=%~dp0app\build\outputs\apk\debug\app-debug.apk"

if not exist "%APK_PATH%" (
    echo [Notice] APK not found. Building APK first...
    call "%~dp0build-apk.bat"
)

echo [1/2] Checking connected Android device...
adb devices
echo.

echo [2/2] Installing AutoTube to Android device...
echo Installing with Play Store identity: -i com.android.vending
adb install -r -i "com.android.vending" "%APK_PATH%"

if %errorlevel% neq 0 (
    echo.
    echo [Retry] Trying standard adb install...
    adb install -r "%APK_PATH%"
)

if %errorlevel% equ 0 (
    echo.
    echo ========================================================
    echo  [SUCCESS] AutoTube installed successfully!
    echo  Please open the 'AutoTube' app on your phone once.
    echo ========================================================
) else (
    echo.
    echo ========================================================
    echo  [FAILED] Installation failed.
    echo  Please ensure:
    echo  1. Your phone is connected via USB.
    echo  2. 'USB Debugging' is enabled in Developer Options.
    echo  3. Tap 'Allow' on your phone when prompted.
    echo ========================================================
)

echo.

