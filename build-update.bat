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

REM ADB Path Configuration
if exist "C:\Android\Sdk\platform-tools\adb.exe" set "PATH=C:\Android\Sdk\platform-tools;%PATH%"
if exist "%LOCALAPPDATA%\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe" set "PATH=%LOCALAPPDATA%\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools;%PATH%"

echo ========================================================
echo   AutoTube - Checking Device & App Installation
echo ========================================================
echo.

set "APK_PATH=%~dp0app\build\outputs\apk\debug\app-debug.apk"

echo [1/3] Checking connected Android device...
adb devices
echo.

REM Verify device connection
set "DEVICE_FOUND="
for /f "skip=1 tokens=1,2" %%a in ('adb devices 2^>nul') do (
    if "%%b"=="device" set "DEVICE_FOUND=1"
)

if not defined DEVICE_FOUND (
    echo [Warning] No active Android device detected.
    echo Please make sure your phone is connected via USB and USB Debugging is allowed.
    echo.
    pause
    exit /b 1
)

echo [2/3] Checking if AutoTube is installed on phone...
set "INSTALLED_PATH="
for /f "tokens=*" %%a in ('adb shell pm path com.personal.andautoutube 2^>nul') do (
    set "INSTALLED_PATH=%%a"
)

if not defined INSTALLED_PATH (
    echo.
    echo ========================================================
    echo  [안내] AutoTube가 기기에 아직 설치되어 있지 않습니다!
    echo.
    echo  안드로이드 오토(실차량) 런처에 정상 등록되려면,
    echo  '최초 1회 설치'는 반드시 KingInstaller로 진행해야 합니다.
    echo.
    echo  [최초 설치 안내]
    echo  1. APK 파일 위치:
    echo     %APK_PATH%
    echo.
    echo  2. 위 APK 파일을 폰에 복사(또는 adb push) 후,
    echo     KingInstaller에서 'Install as king'으로 설치해 주세요.
    echo.
    echo  3. 최초 설치가 완료된 후부터는 이 build-update.bat로
    echo     언제든 코드를 수정하고 바로 업데이트하실 수 있습니다!
    echo ========================================================
    echo.
    pause
    exit /b 0
)

echo [3/3] Updating AutoTube on Android device (preserving installer origin)...
REM Note: DO NOT uninstall to keep KingInstaller's Play Store origin intact!
echo Updating with Play Store identity: adb install -r -d -i com.android.vending
adb install -r -d -i "com.android.vending" "%APK_PATH%"

if %errorlevel% neq 0 (
    echo.
    echo [Retry] Trying standard adb reinstall: adb install -r "%APK_PATH%"
    adb install -r "%APK_PATH%"
)

if %errorlevel% equ 0 (
    echo.
    echo ========================================================
    echo  [SUCCESS] AutoTube updated successfully!
    echo  Preserved KingInstaller Play Store origin.
    echo ========================================================
) else (
    echo.
    echo ========================================================
    echo  [FAILED] Update failed.
    echo  Please ensure:
    echo  1. Your phone screen is unlocked.
    echo  2. Tap 'Allow' on your phone if prompted.
    echo ========================================================
)

echo.
pause
