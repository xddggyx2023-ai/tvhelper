@echo off
setlocal
set ROOT=D:\dev
set JAVA_HOME=%ROOT%\toolchain\jdk-17.0.21.1+2
set ANDROID_HOME=%ROOT%\sdk
set ANDROID_SDK_ROOT=%ANDROID_HOME%
set PATH=%JAVA_HOME%\bin;%PATH%

cd /d %ROOT%\tvhelper
call "%ROOT%\toolchain\gradle-8.11.1\bin\gradle.bat" --no-daemon --console=plain %*

echo.
echo APK: %ROOT%\tvhelper\app\build\outputs\apk\debug\app-debug.apk
endlocal
