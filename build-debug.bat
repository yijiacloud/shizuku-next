@echo off
set JAVA_HOME=D:\jdk
set ANDROID_HOME=D:\Android\Sdk
set ANDROID_SDK_ROOT=D:\Android\Sdk
set GRADLE_USER_HOME=D:\gradle_home
set TEMP=D:\tmp
set TMP=D:\tmp
set PATH=%JAVA_HOME%\bin;%PATH%
cd /d D:\shizukunext
call gradlew.bat :manager:assembleDebug --no-daemon %*
if %ERRORLEVEL%==0 (
    echo.
    echo === Build successful! ===
    powershell -Command "Copy-Item -LiteralPath (Get-Item 'manager\build\outputs\apk\debug\*.apk').FullName -Destination 'out\apk\shizuku-next-debug.apk' -Force"
    echo APK: out\apk\shizuku-next-debug.apk
)
