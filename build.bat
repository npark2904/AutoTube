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
