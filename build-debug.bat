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
    copy /Y "manager\build\outputs\apk\debug\*.apk" "out\apk\shizuku-next-debug.apk"
    echo APK: out\apk\shizuku-next-debug.apk
)
